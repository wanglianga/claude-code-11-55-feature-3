package com.group.carbon.service;

import com.group.carbon.common.BizException;
import com.group.carbon.common.EnergyTypes;
import com.group.carbon.common.Types.*;
import com.group.carbon.dto.Requests.ConversionRuleReq;
import com.group.carbon.dto.Requests.DecideAdjudicationReq;
import com.group.carbon.dto.Requests.FieldInput;
import com.group.carbon.dto.Requests.ReceiveEvidenceReq;
import com.group.carbon.model.*;
import com.group.carbon.repo.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 凭证数量裁定编排服务：
 * 1. 接收端登记凭证：保存文件哈希、抽取字段、单位、订单/交货/退货关系；重发文件一律新建版本；
 * 2. 凭证变更（重发/作废）后，引用它的旧裁定失效并自动重裁，运行已冻结的快照不受影响；
 * 3. 调引擎枚举可行数量组合：唯一解自动裁定，多解/不可换算维持待裁定（待补），由审核员人工决策；
 * 4. 运行启动时把采用数量、理由与证据版本冻结到 run_adjudication_bindings。
 */
@Service
public class EvidenceService {
  private final EvidenceDocumentRepo docRepo;
  private final EvidenceFieldRepo fieldRepo;
  private final UnitConversionRepo conversionRepo;
  private final QuantityAdjudicationRepo adjRepo;
  private final AdjudicationEvidenceRepo adjEvRepo;
  private final RunAdjudicationBindingRepo bindingRepo;
  private final PlantRepo plantRepo;
  private final EvidenceAdjudicationEngine engine;
  private final ObjectMapper objectMapper;

  public EvidenceService(EvidenceDocumentRepo docRepo, EvidenceFieldRepo fieldRepo,
                         UnitConversionRepo conversionRepo, QuantityAdjudicationRepo adjRepo,
                         AdjudicationEvidenceRepo adjEvRepo, RunAdjudicationBindingRepo bindingRepo,
                         PlantRepo plantRepo, EvidenceAdjudicationEngine engine,
                         ObjectMapper objectMapper) {
    this.docRepo = docRepo;
    this.fieldRepo = fieldRepo;
    this.conversionRepo = conversionRepo;
    this.adjRepo = adjRepo;
    this.adjEvRepo = adjEvRepo;
    this.bindingRepo = bindingRepo;
    this.plantRepo = plantRepo;
    this.engine = engine;
    this.objectMapper = objectMapper;
  }

  // ---------------- 换算规则 ----------------

  public List<UnitConversion> listConversions() { return conversionRepo.findByStatusOrderById("ACTIVE"); }

  @Transactional
  public UnitConversion saveRule(String username, ConversionRuleReq req) {
    EnergyTypes.metaOf(req.energyType());
    Optional<UnitConversion> existing = conversionRepo
        .findByEnergyTypeAndFromUnitAndToUnit(req.energyType(), req.fromUnit(), req.toUnit());
    UnitConversion rule = existing.orElseGet(UnitConversion::new);
    rule.setEnergyType(req.energyType());
    rule.setFromUnit(req.fromUnit());
    rule.setToUnit(req.toUnit());
    rule.setFactor(req.factor());
    rule.setRequiresDensity(Boolean.TRUE.equals(req.requiresDensity()));
    rule.setRequiresPackSpec(Boolean.TRUE.equals(req.requiresPackSpec()));
    rule.setDefaultDensity(req.defaultDensity());
    rule.setDefaultPackSpec(req.defaultPackSpec());
    rule.setStatus("ACTIVE");
    rule.setCreatedBy(existing.isPresent() ? existing.get().getCreatedBy() : username);
    return conversionRepo.save(rule);
  }

  @Transactional
  public UnitConversion suspendRule(Long id) {
    UnitConversion rule = conversionRepo.findById(id)
        .orElseThrow(() -> BizException.notFound("换算规则不存在: " + id));
    rule.setStatus("SUSPENDED");
    return conversionRepo.save(rule);
  }

  private Map<String, UnitConversion> ruleMap() {
    Map<String, UnitConversion> map = new HashMap<>();
    for (UnitConversion r : conversionRepo.findByStatusOrderById("ACTIVE")) {
      map.put(EvidenceAdjudicationEngine.ruleKey(r.getEnergyType(), r.getFromUnit(), r.getToUnit()), r);
    }
    return map;
  }

  // ---------------- 凭证接收 ----------------

  /**
   * 接收端登记：同 doc_no 再次接收一律新建版本（version_no 递增），旧版本置 SUPERSEDED。
   * 登记后自动失效引用该凭证的旧裁定并重裁。
   */
  @Transactional
  public EvidenceDocument receive(String username, ReceiveEvidenceReq req) {
    return receive(username, req, true);
  }

  /**
   * 接收端登记：同 doc_no 再次接收一律新建版本（version_no 递增），旧版本置 SUPERSEDED。
   * autoAdjudicate=false 用于批量补录历史凭证，全部登记后统一调用 adjudicate，避免中间态裁定。
   */
  @Transactional
  public EvidenceDocument receive(String username, ReceiveEvidenceReq req, boolean autoAdjudicate) {
    RunService.validatePeriod(req.period());
    plantRepo.findById(req.plantId())
        .orElseThrow(() -> BizException.badRequest("工厂不存在: " + req.plantId()));
    EvidenceDocType docType;
    try {
      docType = EvidenceDocType.valueOf(req.docType());
    } catch (IllegalArgumentException e) {
      throw BizException.badRequest("单据类型必须为 INVOICE/WEIGHNOTE/ENV_DECLARATION/DELIVERY_NOTE/RETURN_NOTE/PURCHASE_ORDER");
    }
    if (req.fileHash() == null || req.fileHash().isBlank()) {
      throw BizException.badRequest("接收端必须保存文件哈希");
    }

    List<EvidenceDocument> sameNo = docRepo.findByDocNoOrderByVersionNoDesc(req.docNo());
    int versionNo = sameNo.isEmpty() ? 1 : sameNo.get(0).getVersionNo() + 1;
    Long supersedesId = null;
    for (EvidenceDocument old : sameNo) {
      if (old.getStatus() == EvidenceDocStatus.RECEIVED) {
        old.setStatus(EvidenceDocStatus.SUPERSEDED);
        // 立即 flush 释放 one_active_doc_version 部分唯一索引，再插入新版本
        docRepo.saveAndFlush(old);
        supersedesId = old.getId();
      }
    }

    EvidenceDocument doc = new EvidenceDocument();
    doc.setDocNo(req.docNo());
    doc.setVersionNo(versionNo);
    doc.setSupersedesId(supersedesId);
    doc.setDocType(docType);
    doc.setSupplier(req.supplier());
    doc.setPlantId(req.plantId());
    doc.setPeriod(req.period());
    doc.setVoucherNo(blankToNull(req.voucherNo()));
    doc.setOrderNo(blankToNull(req.orderNo()));
    doc.setDeliveryNo(blankToNull(req.deliveryNo()));
    doc.setReturnNo(blankToNull(req.returnNo()));
    doc.setMaterialCode(req.materialCode());
    doc.setSigned(Boolean.TRUE.equals(req.signed()));
    doc.setAuthorityTier(req.authorityTier() != null ? req.authorityTier() : defaultTier(docType));
    doc.setDocDate(LocalDate.parse(req.docDate()));
    doc.setFileName(req.fileName());
    doc.setFileHash(req.fileHash().trim());
    doc.setFileSize(req.fileSize());
    doc.setStatus(EvidenceDocStatus.RECEIVED);
    doc.setCreatedBy(username);
    docRepo.save(doc);

    for (FieldInput f : req.fields()) {
      EvidenceField ef = new EvidenceField();
      ef.setDocumentId(doc.getId());
      ef.setFieldName(f.name());
      ef.setFieldText(f.text());
      ef.setNumValue(f.numValue());
      ef.setUnit(f.unit());
      ef.setExtractStatus(f.extractStatus() == null || f.extractStatus().isBlank()
          ? FieldExtractStatus.OK : FieldExtractStatus.valueOf(f.extractStatus()));
      fieldRepo.save(ef);
    }

    // 引用该凭证（同号任意版本）的生效裁定失效并重裁
    if (!sameNo.isEmpty()) {
      invalidateByDocNo(req.docNo(), "凭证 " + req.docNo() + " 收到重发版本 v" + versionNo
          + "，引用旧版本的裁定自动失效");
    }
    if (autoAdjudicate && doc.getVoucherNo() != null) {
      adjudicate(doc.getPeriod(), doc.getPlantId(), doc.getVoucherNo());
    }
    return doc;
  }

  private int defaultTier(EvidenceDocType t) {
    return switch (t) {
      case INVOICE, WEIGHNOTE -> 1;
      case DELIVERY_NOTE, ENV_DECLARATION -> 2;
      case PURCHASE_ORDER, RETURN_NOTE -> 3;
    };
  }

  @Transactional
  public EvidenceDocument voidDocument(Long id, String reason) {
    EvidenceDocument doc = docRepo.findById(id)
        .orElseThrow(() -> BizException.notFound("凭证不存在: " + id));
    if (doc.getStatus() != EvidenceDocStatus.RECEIVED) {
      throw BizException.conflict("只有生效中的凭证可以作废，当前状态: " + doc.getStatus());
    }
    doc.setStatus(EvidenceDocStatus.VOID);
    doc.setVoidReason(reason);
    docRepo.save(doc);
    invalidateByDocNo(doc.getDocNo(), "凭证 " + doc.getDocNo() + " 被作废：" + reason);
    if (doc.getVoucherNo() != null) {
      adjudicate(doc.getPeriod(), doc.getPlantId(), doc.getVoucherNo());
    }
    return doc;
  }

  /** 旧裁定失效：保留历史记录，状态置 INVALID（运行快照中的证据链 JSON 不受影响）。 */
  private void invalidateByDocNo(String docNo, String reason) {
    List<EvidenceDocument> versions = docRepo.findByDocNoOrderByVersionNoDesc(docNo);
    for (EvidenceDocument d : versions) {
      for (AdjudicationEvidence ev : adjEvRepo.findByDocumentId(d.getId())) {
        QuantityAdjudication adj = adjRepo.findById(ev.getAdjudicationId()).orElse(null);
        if (adj != null && adj.getStatus() != AdjudicationStatus.INVALID) {
          adj.setStatus(AdjudicationStatus.INVALID);
          adj.setInvalidatedReason(reason);
          adj.setInvalidatedAt(LocalDateTime.now());
          adjRepo.saveAndFlush(adj);
        }
      }
    }
  }

  // ---------------- 裁定 ----------------

  public List<EvidenceDocument> listDocs(String period, String voucherNo) {
    if (voucherNo != null && !voucherNo.isBlank()) return docRepo.findByVoucherNoOrderByVersionNoDesc(voucherNo);
    if (period != null && !period.isBlank()) return docRepo.findByPeriodOrderByIdDesc(period);
    return docRepo.findAll();
  }

  public Map<String, Object> docDetail(Long id) {
    EvidenceDocument doc = docRepo.findById(id)
        .orElseThrow(() -> BizException.notFound("凭证不存在: " + id));
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("document", doc);
    out.put("fields", fieldRepo.findByDocumentIdOrderById(id));
    out.put("versions", docRepo.findByDocNoOrderByVersionNoDesc(doc.getDocNo()));
    return out;
  }

  public List<QuantityAdjudication> listAdjudications(String period, String status) {
    List<QuantityAdjudication> all = period != null && !period.isBlank()
        ? adjRepo.findByPeriodOrderByIdDesc(period) : adjRepo.findAll();
    return all.stream()
        .filter(a -> status == null || status.isBlank() || a.getStatus().name().equals(status))
        .toList();
  }

  public Map<String, Object> adjudicationDetail(Long id) {
    QuantityAdjudication adj = adjRepo.findById(id)
        .orElseThrow(() -> BizException.notFound("裁定不存在: " + id));
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("adjudication", adj);
    out.put("evidence", adjEvRepo.findByAdjudicationIdOrderById(id));
    // 实时重算可行组合：人工裁定的采用量必须落在该集合内
    if (adj.getStatus() != AdjudicationStatus.INVALID) {
      EvidenceAdjudicationEngine.Result result = reevaluate(adj);
      List<Map<String, Object>> candidates = new ArrayList<>();
      for (EvidenceAdjudicationEngine.Combo c : result.feasibleCombos()) {
        Map<String, Object> cm = new LinkedHashMap<>();
        cm.put("netQty", c.netQty());
        cm.put("docIds", c.docIds());
        cm.put("eventKeys", c.eventKeys());
        candidates.add(cm);
      }
      out.put("feasibleCandidates", candidates);
      out.put("pruned", result.pruned());
      out.put("ruleSummary", result.ruleSummary());
    } else {
      out.put("feasibleCandidates", List.of());
    }
    return out;
  }

  /**
   * 执行裁定：收集该凭证号下全部生效凭证（含同订单的采购订单与关联退货单），
   * 换算到共同单位后枚举可行组合；唯一解自动裁定，多解或不可换算维持待裁定。
   */
  @Transactional
  public QuantityAdjudication adjudicate(String period, Long plantId, String voucherNo) {
    List<EvidenceDocument> direct = docRepo.findByVoucherNoOrderByVersionNoDesc(voucherNo).stream()
        .filter(d -> d.getPeriod().equals(period) && d.getPlantId().equals(plantId)
            && d.getStatus() == EvidenceDocStatus.RECEIVED)
        .toList();
    if (direct.isEmpty()) {
      throw BizException.badRequest("凭证号 " + voucherNo + " 在 " + period + " 工厂#" + plantId + " 下没有生效凭证，无法裁定");
    }
    Set<String> orderNos = new HashSet<>();
    for (EvidenceDocument d : direct) {
      if (d.getOrderNo() != null) orderNos.add(d.getOrderNo());
    }
    // 同订单的采购订单/退货单即使未登记凭证号也纳入守恒约束
    List<EvidenceDocument> related = new ArrayList<>(direct);
    for (String orderNo : orderNos) {
      for (EvidenceDocument d : docRepo.findByOrderNoOrderByIdDesc(orderNo)) {
        if (d.getStatus() == EvidenceDocStatus.RECEIVED
            && d.getPeriod().equals(period) && d.getPlantId().equals(plantId)
            && related.stream().noneMatch(x -> x.getId().equals(d.getId()))) {
          related.add(d);
        }
      }
    }

    String energyType = direct.stream().map(EvidenceDocument::getMaterialCode)
        .filter(Objects::nonNull).findFirst()
        .orElseThrow(() -> BizException.badRequest("凭证缺少物料/能源类型，无法确定共同单位"));
    String targetUnit = EnergyTypes.metaOf(energyType).unit();
    Map<String, UnitConversion> rules = ruleMap();

    List<EvidenceAdjudicationEngine.Candidate> candidates = new ArrayList<>();
    for (EvidenceDocument d : related) {
      List<EvidenceField> fields = fieldRepo.findByDocumentIdOrderById(d.getId());
      EvidenceField qf = findField(fields, "QUANTITY");
      EvidenceField uf = findField(fields, "UNIT");
      EvidenceField df = findField(fields, "DENSITY");
      EvidenceField pf = findField(fields, "PACK_SPEC");
      candidates.add(new EvidenceAdjudicationEngine.Candidate(
          d.getId(), d.getDocNo(), d.getVersionNo(), d.getDocType(), d.getFileHash(),
          d.isSigned(), d.getAuthorityTier(), d.getDocDate(),
          d.getOrderNo(), d.getDeliveryNo(), d.getReturnNo(),
          energyType,
          qf != null ? qf.getNumValue() : null,
          uf != null ? uf.getUnit() : null,
          df != null ? df.getNumValue() : null,
          pf != null ? pf.getNumValue() : null));
    }

    EvidenceAdjudicationEngine.Result result = engine.run(
        candidates, new EvidenceAdjudicationEngine.ConstraintContext(null, targetUnit), rules);

    QuantityAdjudication old = adjRepo.findFirstByPeriodAndPlantIdAndVoucherNoAndStatusIn(
        period, plantId, voucherNo, List.of(AdjudicationStatus.DECIDED, AdjudicationStatus.PENDING))
        .orElse(null);
    Long supersedesId = null;
    if (old != null) {
      old.setStatus(AdjudicationStatus.INVALID);
      old.setInvalidatedReason("凭证集合变化触发自动重裁");
      old.setInvalidatedAt(LocalDateTime.now());
      // 立即 flush：先释放部分唯一索引占坑，再插入新裁定（Hibernate 默认 insert 先于 update）
      adjRepo.saveAndFlush(old);
      supersedesId = old.getId();
    }

    QuantityAdjudication adj = new QuantityAdjudication();
    adj.setAdjNo(RunService.newNo("ADJ"));
    adj.setPeriod(period);
    adj.setPlantId(plantId);
    adj.setVoucherNo(voucherNo);
    adj.setOrderNo(orderNos.stream().findFirst().orElse(null));
    adj.setEnergyType(energyType);
    adj.setAdoptedUnit(targetUnit);
    adj.setCandidateCount(result.feasibleTotals().size());
    adj.setPruned(result.pruned());
    adj.setDecisionRule(result.ruleSummary());
    adj.setSupersedesAdjId(supersedesId);

    boolean hasUnconvertible = result.allConverted().stream()
        .anyMatch(cv -> cv.status() == ConversionStatus.UNCONVERTIBLE
            && cv.c().docType() != EvidenceDocType.PURCHASE_ORDER);
    EvidenceAdjudicationEngine.Combo adopted = null;
    if (!hasUnconvertible && result.feasibleTotals().size() == 1) {
      BigDecimal only = result.feasibleTotals().first();
      adopted = result.feasibleCombos().stream()
          .filter(c -> c.netQty().compareTo(only) == 0)
          .max(Comparator.comparingInt(c -> engine.comboScore(c, result)))
          .orElse(null);
      adj.setStatus(AdjudicationStatus.DECIDED);
      adj.setAdoptedQty(only);
      adj.setDecidedBy("system");
      adj.setDecidedAt(LocalDateTime.now());
      adj.setReason(buildAutoReason(result, adopted));
    } else {
      adj.setStatus(AdjudicationStatus.PENDING);
      adj.setReason(hasUnconvertible
          ? "存在无法换算到共同单位 " + targetUnit + " 的凭证（密度或包装规格缺失），维持待补，不得用金额或平均数填活动量"
          : "守恒约束下存在 " + result.feasibleTotals().size()
              + " 个互斥可行数量组合，不能挑选看起来最完整的单据，待审核员人工裁定");
    }
    adjRepo.save(adj);
    persistEvidence(adj, result, adopted);
    return adj;
  }

  /** 人工裁定：采用数量必须是引擎枚举出来的可行组合之一，禁止凭空填数。 */
  @Transactional
  public QuantityAdjudication decide(Long adjId, String username, DecideAdjudicationReq req) {
    QuantityAdjudication adj = adjRepo.findById(adjId)
        .orElseThrow(() -> BizException.notFound("裁定不存在: " + adjId));
    if (adj.getStatus() == AdjudicationStatus.DECIDED) {
      throw BizException.conflict("裁定已作出且生效；凭证变更后应通过重裁产生新裁定，不能直接改写");
    }
    if (adj.getStatus() == AdjudicationStatus.INVALID) {
      throw BizException.conflict("裁定已失效，请对最新待裁定记录作出决策");
    }
    EvidenceAdjudicationEngine.Result result = reevaluate(adj);
    BigDecimal qty = req.adoptedQty().setScale(3);
    boolean feasible = result.feasibleTotals().stream().anyMatch(t -> t.compareTo(qty) == 0);
    if (!feasible) {
      throw BizException.conflict("采用数量 " + qty.toPlainString() + " " + adj.getAdoptedUnit()
          + " 不在守恒约束的可行集合 " + result.feasibleTotals()
          + " 内；不允许用金额或平均数填活动量");
    }
    EvidenceAdjudicationEngine.Combo adopted = result.feasibleCombos().stream()
        .filter(c -> c.netQty().compareTo(qty) == 0)
        .max(Comparator.comparingInt(c -> engine.comboScore(c, result)))
        .orElseThrow();
    adj.setStatus(AdjudicationStatus.DECIDED);
    adj.setAdoptedQty(qty);
    adj.setDecidedBy(username);
    adj.setDecidedAt(LocalDateTime.now());
    adj.setReason("人工裁定：" + req.reason() + "；" + buildAutoReason(result, adopted));
    adjRepo.save(adj);
    adjEvRepo.deleteAll(adjEvRepo.findByAdjudicationIdOrderById(adjId));
    persistEvidence(adj, result, adopted);
    return adj;
  }

  /** 按裁定记录的凭证集合重新跑一遍引擎（人工决策与详情复核用）。 */
  private EvidenceAdjudicationEngine.Result reevaluate(QuantityAdjudication adj) {
    List<AdjudicationEvidence> saved = adjEvRepo.findByAdjudicationIdOrderById(adj.getId());
    Set<Long> docIds = new LinkedHashSet<>();
    saved.forEach(e -> docIds.add(e.getDocumentId()));
    List<EvidenceAdjudicationEngine.Candidate> candidates = new ArrayList<>();
    for (Long docId : docIds) {
      EvidenceDocument d = docRepo.findById(docId).orElseThrow();
      List<EvidenceField> fields = fieldRepo.findByDocumentIdOrderById(docId);
      EvidenceField qf = findField(fields, "QUANTITY");
      EvidenceField uf = findField(fields, "UNIT");
      EvidenceField df = findField(fields, "DENSITY");
      EvidenceField pf = findField(fields, "PACK_SPEC");
      candidates.add(new EvidenceAdjudicationEngine.Candidate(
          d.getId(), d.getDocNo(), d.getVersionNo(), d.getDocType(), d.getFileHash(),
          d.isSigned(), d.getAuthorityTier(), d.getDocDate(),
          d.getOrderNo(), d.getDeliveryNo(), d.getReturnNo(),
          adj.getEnergyType(),
          qf != null ? qf.getNumValue() : null, uf != null ? uf.getUnit() : null,
          df != null ? df.getNumValue() : null, pf != null ? pf.getNumValue() : null));
    }
    return engine.run(candidates,
        new EvidenceAdjudicationEngine.ConstraintContext(null, adj.getAdoptedUnit()), ruleMap());
  }

  private void persistEvidence(QuantityAdjudication adj,
                               EvidenceAdjudicationEngine.Result result,
                               EvidenceAdjudicationEngine.Combo adopted) {
    Map<Long, EvidenceRole> roles = adopted != null
        ? engine.classify(result, adopted) : Map.of();
    Map<String, String> exclusiveGroups = new HashMap<>();
    for (EvidenceAdjudicationEngine.EventBlock b : result.events()) {
      if (b.options().size() > 1) {
        b.options().stream()
            .flatMap(op -> op.backing().stream())
            .forEach(cv -> exclusiveGroups.put(cv.c().docNo(), b.eventKey()));
      }
    }
    for (EvidenceAdjudicationEngine.Converted cv : result.allConverted()) {
      EvidenceDocument d = docRepo.findById(cv.c().docId()).orElseThrow();
      AdjudicationEvidence ev = new AdjudicationEvidence();
      ev.setAdjudicationId(adj.getId());
      ev.setDocumentId(d.getId());
      ev.setDocNo(d.getDocNo());
      ev.setDocVersionNo(d.getVersionNo());
      ev.setDocType(d.getDocType());
      ev.setDocHash(d.getFileHash()); // 证据版本冻结：记录引用时刻哈希
      ev.setDocSigned(d.isSigned());
      ev.setAuthorityTier(d.getAuthorityTier());
      ev.setDocDate(d.getDocDate());
      ev.setQtyNative(cv.c().qtyNative());
      ev.setUnitNative(cv.c().unitNative());
      ev.setQtyConverted(cv.qtyConverted());
      ev.setConversionStatus(cv.status());
      ev.setExclusiveGroup(exclusiveGroups.get(d.getDocNo()));
      if (adopted != null) {
        ev.setRole(roles.getOrDefault(d.getId(), EvidenceRole.EXCLUDED));
        ev.setExcludeReason(ev.getRole() == EvidenceRole.ADOPTED
            ? "纳入采用组合，换算后 " + (cv.qtyConverted() != null ? cv.qtyConverted().toPlainString() : "?")
                + " " + adj.getAdoptedUnit() + "（" + cv.convertNote() + "）"
            : engine.excludeReason(cv, result, adopted));
      } else {
        ev.setRole(cv.status() == ConversionStatus.UNCONVERTIBLE
            ? EvidenceRole.EXCLUDED : EvidenceRole.SUPPORTING);
        ev.setExcludeReason(cv.status() == ConversionStatus.UNCONVERTIBLE
            ? "单位无法换算：" + cv.convertNote() : "待裁定凭证（可行组合未唯一）");
      }
      adjEvRepo.save(ev);
    }
  }

  private String buildAutoReason(EvidenceAdjudicationEngine.Result result,
                                 EvidenceAdjudicationEngine.Combo adopted) {
    long adoptedCount = adopted.docIds().size();
    long excluded = result.allConverted().size() - adoptedCount;
    return "守恒约束枚举得到唯一可行净量 " + adopted.netQty().toPlainString()
        + "，采用凭证 " + adoptedCount + " 份、排除/参照 " + excluded
        + " 份；排除项均记录互斥组与理由，未使用金额或平均数";
  }

  private EvidenceField findField(List<EvidenceField> fields, String name) {
    return fields.stream().filter(f -> f.getFieldName().equals(name)).findFirst().orElse(null);
  }

  private String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }

  // ---------------- 运行快照 ----------------

  /** 运行启动时冻结该期间全部生效裁定（数量、理由、证据版本）。 */
  @Transactional
  public List<RunAdjudicationBinding> snapshotForRun(Long runId, String period) {
    List<RunAdjudicationBinding> snapshots = new ArrayList<>();
    for (QuantityAdjudication adj : adjRepo.findByPeriodAndStatus(period, AdjudicationStatus.DECIDED)) {
      RunAdjudicationBinding b = new RunAdjudicationBinding();
      b.setRunId(runId);
      b.setAdjudicationId(adj.getId());
      b.setAdjNo(adj.getAdjNo());
      b.setVoucherNo(adj.getVoucherNo());
      b.setPlantId(adj.getPlantId());
      b.setEnergyType(adj.getEnergyType());
      b.setAdoptedQty(adj.getAdoptedQty());
      b.setAdoptedUnit(adj.getAdoptedUnit());
      b.setDecisionRule(adj.getDecisionRule());
      b.setReason(adj.getReason());
      List<AdjudicationEvidence> evs = adjEvRepo.findByAdjudicationIdOrderById(adj.getId());
      try {
        b.setEvidenceChain(objectMapper.writeValueAsString(evs));
      } catch (Exception e) {
        throw new IllegalStateException("证据链快照序列化失败", e);
      }
      snapshots.add(bindingRepo.save(b));
    }
    return snapshots;
  }

  public List<RunAdjudicationBinding> bindingsOfRun(Long runId) {
    return bindingRepo.findByRunIdOrderById(runId);
  }

  /** 运行执行期查询：某凭证号在该运行冻结快照中的采用量（未冻结到裁定则为空）。 */
  public Optional<RunAdjudicationBinding> bindingFor(Long runId, Long plantId, String voucherNo) {
    return bindingRepo.findByRunIdOrderById(runId).stream()
        .filter(b -> b.getPlantId().equals(plantId) && b.getVoucherNo().equals(voucherNo))
        .findFirst();
  }

  /** 血缘下钻用：裁定头 + 证据条目（若裁定后续失效，运行快照中的证据链 JSON 仍独立保留）。 */
  public Map<String, Object> adjudicationBinding(Long adjudicationId) {
    QuantityAdjudication adj = adjRepo.findById(adjudicationId)
        .orElseThrow(() -> BizException.notFound("裁定不存在: " + adjudicationId));
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("adjudication", adj);
    out.put("evidence", adjEvRepo.findByAdjudicationIdOrderById(adjudicationId));
    return out;
  }

  /** 该期间待裁定（含待补）凭证号清单，供前端与仪表盘提示。 */
  public List<QuantityAdjudication> pendingOfPeriod(String period) {
    return adjRepo.findByPeriodAndStatus(period, AdjudicationStatus.PENDING);
  }
}
