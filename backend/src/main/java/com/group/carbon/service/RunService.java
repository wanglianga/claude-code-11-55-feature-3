package com.group.carbon.service;

import com.group.carbon.common.BizException;
import com.group.carbon.common.EnergyTypes;
import com.group.carbon.common.Types.*;
import com.group.carbon.model.*;
import com.group.carbon.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 核算运行编排：
 * 1. 启动时快照当前已批准因子清单（run_factor_bindings），运行期间因子被替换不影响本运行；
 * 2. 按 工厂地域 + 能源类型 + 适用期间 匹配批准因子，缺因子只把对应指标列为待核，不用集团平均数兜底；
 * 3. 结果与来源行、因子版本建立血缘。
 */
@Service
public class RunService {
  private final CalcRunRepo runRepo;
  private final RunFactorBindingRepo bindingRepo;
  private final EmissionResultRepo resultRepo;
  private final EmissionLineageRepo lineageRepo;
  private final SourceRowRepo rowRepo;
  private final SourceBatchRepo batchRepo;
  private final FactorRepo factorRepo;
  private final FactorVersionRepo versionRepo;
  private final PlantRepo plantRepo;
  private final EvidenceService evidenceService;

  public RunService(CalcRunRepo runRepo, RunFactorBindingRepo bindingRepo,
                    EmissionResultRepo resultRepo, EmissionLineageRepo lineageRepo,
                    SourceRowRepo rowRepo, SourceBatchRepo batchRepo,
                    FactorRepo factorRepo, FactorVersionRepo versionRepo, PlantRepo plantRepo,
                    EvidenceService evidenceService) {
    this.runRepo = runRepo;
    this.bindingRepo = bindingRepo;
    this.resultRepo = resultRepo;
    this.lineageRepo = lineageRepo;
    this.rowRepo = rowRepo;
    this.batchRepo = batchRepo;
    this.factorRepo = factorRepo;
    this.versionRepo = versionRepo;
    this.plantRepo = plantRepo;
    this.evidenceService = evidenceService;
  }

  public static void validatePeriod(String period) {
    if (period == null || !period.matches("\\d{4}-(0[1-9]|1[0-2])")) {
      throw BizException.badRequest("期间格式必须为 YYYY-MM，例如 2025-06");
    }
  }

  /** 创建正式运行：绑定启动时刻的批准因子清单。并发重复运行由数据库唯一索引拦截。 */
  @Transactional
  public CalcRun createOfficialRun(String period, String username) {
    validatePeriod(period);
    CalcRun run = new CalcRun();
    run.setRunNo(newNo("RUN"));
    run.setPeriod(period);
    run.setPurpose(RunPurpose.OFFICIAL);
    run.setStatus(RunStatus.RUNNING);
    run.setTriggeredBy(username);
    run.setStartedAt(LocalDateTime.now());
    runRepo.save(run);
    snapshotBindings(run);
    return run;
  }

  /** 候选运行（影响评估用）：同步创建并立即执行完成。 */
  @Transactional
  public CalcRun createCandidateRun(String period, String triggeredBy) {
    validatePeriod(period);
    CalcRun run = new CalcRun();
    run.setRunNo(newNo("CAND"));
    run.setPeriod(period);
    run.setPurpose(RunPurpose.CANDIDATE);
    run.setStatus(RunStatus.RUNNING);
    run.setTriggeredBy(triggeredBy);
    run.setStartedAt(LocalDateTime.now());
    runRepo.save(run);
    snapshotBindings(run);
    execute(run.getId());
    return runRepo.findById(run.getId()).orElseThrow();
  }

  private void snapshotBindings(CalcRun run) {
    LocalDate periodStart = LocalDate.parse(run.getPeriod() + "-01");
    List<FactorVersion> approved = versionRepo.findByStatus(FactorStatus.APPROVED);
    // 同一因子若存在多个覆盖该期间的批准版本，取版本号最大者
    Map<Long, FactorVersion> byFactor = new LinkedHashMap<>();
    for (FactorVersion v : approved) {
      boolean covers = !v.getValidFrom().isAfter(periodStart)
          && (v.getValidTo() == null || !v.getValidTo().isBefore(periodStart));
      if (!covers) continue;
      FactorVersion prev = byFactor.get(v.getFactorId());
      if (prev == null || v.getVersionNo() > prev.getVersionNo()) byFactor.put(v.getFactorId(), v);
    }
    for (FactorVersion v : byFactor.values()) {
      Factor f = factorRepo.findById(v.getFactorId()).orElseThrow();
      RunFactorBinding b = new RunFactorBinding();
      b.setRunId(run.getId());
      b.setFactorVersionId(v.getId());
      b.setFactorCode(f.getCode());
      b.setRegion(f.getRegion());
      b.setEnergyType(f.getEnergyType());
      b.setValue(v.getValue());
      b.setValidFrom(v.getValidFrom());
      b.setValidTo(v.getValidTo());
      bindingRepo.save(b);
    }
    // 同步冻结该期间的数量裁定快照（数量+理由+证据版本），运行中重裁不影响本运行
    evidenceService.snapshotForRun(run.getId(), run.getPeriod());
  }

  private record GroupKey(Long plantId, Long productLineId, String energyType) {}

  /** 核算时解析后的来源行：qty 为裁定采用量（待证时为空），adjudicationId 指向裁定快照。 */
  private record ResolvedRow(SourceRow row, BigDecimal qty, Long adjudicationId) {}

  /** 执行核算：仅使用启动时绑定的因子清单；结果与来源行建立血缘。 */
  @Transactional
  public void execute(Long runId) {
    CalcRun run = runRepo.findById(runId).orElseThrow(() -> BizException.notFound("运行不存在"));
    if (run.getStatus() != RunStatus.RUNNING) return;
    try {
      List<RunFactorBinding> bindings = bindingRepo.findByRunId(runId);
      List<SourceRow> rows = rowRepo.findActiveVerifiedByPeriod(run.getPeriod(), RowStatus.ACTIVE);
      Map<Long, Plant> plants = plantRepo.findAll().stream()
          .collect(Collectors.toMap(Plant::getId, p -> p));

      Map<GroupKey, List<SourceRow>> groups = new LinkedHashMap<>();
      for (SourceRow r : rows) {
        groups.computeIfAbsent(new GroupKey(r.getPlantId(), r.getProductLineId(), r.getEnergyType()),
            k -> new ArrayList<>()).add(r);
      }

      for (Map.Entry<GroupKey, List<SourceRow>> e : groups.entrySet()) {
        GroupKey key = e.getKey();
        Plant plant = plants.get(key.plantId());
        if (plant == null) throw BizException.badRequest("来源行引用了不存在的工厂: " + key.plantId());
        boolean procurement = "PROCUREMENT".equals(
            EnergyTypes.metaOf(key.energyType()).category());

        // 采购行：活动量只能来自数量裁定快照；未冻结到生效裁定 → 待证（不用金额/平均数填）
        List<ResolvedRow> resolved = new ArrayList<>();
        boolean pendingEvidence = false;
        if (procurement) {
          for (SourceRow r : e.getValue()) {
            Optional<RunAdjudicationBinding> b =
                evidenceService.bindingFor(runId, r.getPlantId(), r.getVoucherNo());
            if (b.isEmpty()) {
              pendingEvidence = true;
              resolved.add(new ResolvedRow(r, null, null));
            } else {
              resolved.add(new ResolvedRow(r, b.get().getAdoptedQty(), b.get().getAdjudicationId()));
            }
          }
        } else {
          for (SourceRow r : e.getValue()) resolved.add(new ResolvedRow(r, r.getQuantity(), null));
        }

        BigDecimal qty = pendingEvidence ? null : resolved.stream()
            .map(x -> x.qty).reduce(BigDecimal.ZERO, BigDecimal::add);

        RunFactorBinding binding = bindings.stream()
            .filter(bb -> bb.getEnergyType().equals(key.energyType())
                && bb.getRegion().equals(plant.getRegion()))
            .findFirst().orElse(null);

        EmissionResult res = new EmissionResult();
        res.setRunId(runId);
        res.setPlantId(key.plantId());
        res.setProductLineId(key.productLineId());
        res.setScope(EnergyTypes.scopeOf(key.energyType()));
        res.setEnergyType(key.energyType());
        res.setActivityQty(qty);
        res.setUnit(EnergyTypes.unitOf(key.energyType()));
        if (pendingEvidence) {
          // 采购数量未裁定/待补：指标待证，活动量与排放量留空，披露环节阻断
          res.setStatus(ResultStatus.PENDING_EVIDENCE);
        } else if (binding != null) {
          res.setFactorVersionId(binding.getFactorVersionId());
          res.setFactorValue(binding.getValue());
          res.setEmissionTco2e(qty.multiply(binding.getValue())
              .divide(new BigDecimal("1000"), 6, RoundingMode.HALF_UP));
          res.setStatus(ResultStatus.OK);
        } else {
          // 缺少适用批准因子：指标列为待核，不用集团平均因子兜底
          res.setStatus(ResultStatus.PENDING_FACTOR);
        }
        resultRepo.save(res);
        for (ResolvedRow rr : resolved) {
          lineageRepo.save(new EmissionLineage(res.getId(), rr.row.getId(), rr.qty, rr.adjudicationId));
        }
      }
      run.setStatus(RunStatus.COMPLETED);
      run.setCompletedAt(LocalDateTime.now());
      runRepo.save(run);
    } catch (Exception ex) {
      run.setStatus(RunStatus.FAILED);
      run.setError(ex.getMessage());
      run.setCompletedAt(LocalDateTime.now());
      runRepo.save(run);
    }
  }

  // ---------------- 查询 ----------------

  public List<CalcRun> listRuns() { return runRepo.findAllByOrderByIdDesc(); }

  public CalcRun getRun(Long id) {
    return runRepo.findById(id).orElseThrow(() -> BizException.notFound("运行不存在: " + id));
  }

  public List<RunFactorBinding> bindingsOf(Long runId) { return bindingRepo.findByRunId(runId); }

  /** 运行冻结的数量裁定快照（采用量、理由、证据版本）。 */
  public List<RunAdjudicationBinding> adjudicationBindingsOf(Long runId) {
    return evidenceService.bindingsOfRun(runId);
  }

  public List<EmissionResult> resultsOf(Long runId) { return resultRepo.findByRunIdOrderById(runId); }

  /** 血缘下钻：某条排放结果 ← 哪份来源行（表计/凭证）+ 哪版因子。 */
  public Map<String, Object> lineageOf(Long resultId) {
    EmissionResult res = resultRepo.findById(resultId)
        .orElseThrow(() -> BizException.notFound("排放结果不存在: " + resultId));
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("result", res);

    Map<String, Object> factorInfo = null;
    if (res.getFactorVersionId() != null) {
      FactorVersion v = versionRepo.findById(res.getFactorVersionId()).orElse(null);
      if (v != null) {
        Factor f = factorRepo.findById(v.getFactorId()).orElse(null);
        factorInfo = new LinkedHashMap<>();
        factorInfo.put("factorCode", f != null ? f.getCode() : null);
        factorInfo.put("factorName", f != null ? f.getName() : null);
        factorInfo.put("region", f != null ? f.getRegion() : null);
        factorInfo.put("versionId", v.getId());
        factorInfo.put("versionNo", v.getVersionNo());
        factorInfo.put("value", v.getValue());
        factorInfo.put("validFrom", v.getValidFrom());
        factorInfo.put("validTo", v.getValidTo());
        factorInfo.put("status", v.getStatus());
        factorInfo.put("approvedBy", v.getApprovedBy());
        factorInfo.put("approvedAt", v.getApprovedAt());
      }
    }
    out.put("factorVersion", factorInfo);

    List<EmissionLineage> edges = lineageRepo.findByResultId(resultId);
    List<Map<String, Object>> sources = new ArrayList<>();
    for (EmissionLineage edge : edges) {
      SourceRow row = rowRepo.findById(edge.getSourceRowId()).orElse(null);
      if (row == null) continue;
      SourceBatch batch = batchRepo.findById(row.getBatchId()).orElse(null);
      Map<String, Object> s = new LinkedHashMap<>();
      s.put("rowId", row.getId());
      s.put("rowNo", row.getRowNo());
      s.put("batchId", row.getBatchId());
      s.put("batchNo", batch != null ? batch.getBatchNo() : null);
      s.put("batchStatus", batch != null ? batch.getStatus() : null);
      s.put("verifiedBy", batch != null ? batch.getVerifiedBy() : null);
      s.put("verifiedAt", batch != null ? batch.getVerifiedAt() : null);
      s.put("uploadedBy", batch != null ? batch.getUploadedBy() : null);
      s.put("voucherNo", row.getVoucherNo());
      s.put("quantity", row.getQuantity());
      s.put("qtyUsed", edge.getQtyUsed());
      s.put("unit", row.getUnit());
      s.put("rowStatus", row.getStatus());
      s.put("adjudicationId", edge.getAdjudicationId());
      // 采购行：下钻数量裁定（采用量、理由、证据版本、排除候选）
      if (edge.getAdjudicationId() != null) {
        s.put("adjudication", evidenceService.adjudicationBinding(edge.getAdjudicationId()));
      }
      sources.add(s);
    }
    out.put("sources", sources);
    return out;
  }

  public static String newNo(String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
  }
}
