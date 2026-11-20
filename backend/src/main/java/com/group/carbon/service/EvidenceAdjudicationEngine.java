package com.group.carbon.service;

import com.group.carbon.common.Types.ConversionStatus;
import com.group.carbon.common.Types.EvidenceDocType;
import com.group.carbon.common.Types.EvidenceRole;
import com.group.carbon.model.UnitConversion;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * 凭证数量裁定引擎（无状态纯逻辑，便于审计复核）。
 *
 * 实现路线：把各凭证换算到共同单位后，按订单、收货、退货与批次建立守恒约束，
 * 枚举全部可行数量组合，暴露拆分交货与重复入账；组合空间受控剪枝。
 * 事件内排序使用显式判定规则（来源权威性层级、签章、发生时点、生效状态）作为 tie-break，
 * 不按金额或平均数填活动量，也不预先指定“简单/复杂证据”分流。
 */
@Component
public class EvidenceAdjudicationEngine {

  /** 组合枚举上限：超过即按显式规则剪枝并在裁定上标记 pruned，控制组合爆炸。 */
  public static final int MAX_COMBOS = 256;
  private static final BigDecimal EPS = new BigDecimal("0.001");

  /** 输入：一份生效凭证及其抽取字段。 */
  public record Candidate(Long docId, String docNo, int versionNo, EvidenceDocType docType,
                          String fileHash, boolean signed, int authorityTier,
                          java.time.LocalDate docDate,
                          String orderNo, String deliveryNo, String returnNo,
                          String energyType,
                          BigDecimal qtyNative, String unitNative,
                          BigDecimal density, BigDecimal packSpec) {}

  /** 输入：守恒约束基线（采购订单换算后的订购量，可为空表示无订单约束）。 */
  public record ConstraintContext(BigDecimal orderedQty, String targetUnit) {}

  /** 换算后的候选凭证。 */
  public record Converted(Candidate c, BigDecimal qtyConverted, ConversionStatus status, String convertNote) {}

  /** 一个交货事件的可选取值：value=该事件收货量（共同单位），backing=支撑该取值的凭证。 */
  public record EventOption(BigDecimal value, List<Converted> backing) {}

  /** 交货事件块：互斥取值 options + 无法换算的凭证 + 订单基线；fullyEvidenced=双证印证须强制纳入。 */
  public record EventBlock(String eventKey, String deliveryNo, String orderNo,
                           List<EventOption> options, List<Converted> unconvertible,
                           List<Converted> orderDocs, boolean fullyEvidenced) {}

  /** 枚举产出的一个可行组合。 */
  public record Combo(Set<Long> docIds, BigDecimal netQty, List<String> eventKeys) {}

  public record Result(List<Combo> feasibleCombos,
                       TreeSet<BigDecimal> feasibleTotals,
                       List<Converted> allConverted,
                       List<EventBlock> events,
                       List<Converted> returns,
                       boolean pruned,
                       String ruleSummary) {}

  // ---------------- 单位换算 ----------------

  /** 换算规则键：物料 + 原生单位 → 共同单位（不同物料的同名单位规则互不覆盖）。 */
  public static String ruleKey(String energyType, String fromUnit, String toUnit) {
    return energyType + "|" + fromUnit.toUpperCase(Locale.ROOT) + "->" + toUnit;
  }

  /** 凭证原生单位 → 共同单位；缺密度/包装规格且规则库无默认值时返回不可换算（维持待补）。 */
  public Converted convert(Candidate c, String targetUnit, Map<String, UnitConversion> rules) {
    if (c.qtyNative() == null || c.unitNative() == null) {
      return new Converted(c, null, ConversionStatus.UNCONVERTIBLE, "凭证缺少数量或单位抽取字段");
    }
    if (c.unitNative().equalsIgnoreCase(targetUnit)) {
      return new Converted(c, c.qtyNative().setScale(3, RoundingMode.HALF_UP),
          ConversionStatus.OK, "原生单位即共同单位 " + targetUnit);
    }
    UnitConversion rule = rules.get(ruleKey(c.energyType(), c.unitNative(), targetUnit));
    if (rule == null || !"ACTIVE".equals(rule.getStatus())) {
      return new Converted(c, null, ConversionStatus.UNCONVERTIBLE,
          "无生效换算规则: " + c.energyType() + " " + c.unitNative() + "→" + targetUnit + "，维持待补");
    }
    if (rule.getFactor() != null) {
      BigDecimal v = c.qtyNative().multiply(rule.getFactor()).setScale(3, RoundingMode.HALF_UP);
      return new Converted(c, v, ConversionStatus.OK,
          "固定系数 " + rule.getFactor().stripTrailingZeros().toPlainString());
    }
    if (rule.isRequiresDensity()) {
      BigDecimal density = c.density() != null ? c.density() : rule.getDefaultDensity();
      if (density == null) {
        return new Converted(c, null, ConversionStatus.UNCONVERTIBLE,
            "体积单位 " + c.unitNative() + " 换算需密度，凭证抽取值与规则库默认值均缺失，维持待补");
      }
      BigDecimal v = c.qtyNative().multiply(density).setScale(3, RoundingMode.HALF_UP);
      return new Converted(c, v, ConversionStatus.OK,
          "体积×密度 " + density.stripTrailingZeros().toPlainString() + " t/m3");
    }
    if (rule.isRequiresPackSpec()) {
      BigDecimal pack = c.packSpec() != null ? c.packSpec() : rule.getDefaultPackSpec();
      if (pack == null) {
        return new Converted(c, null, ConversionStatus.UNCONVERTIBLE,
            "计件单位 " + c.unitNative() + " 换算需包装规格，凭证抽取值与规则库默认值均缺失，维持待补");
      }
      BigDecimal v = c.qtyNative().multiply(pack)
          .divide(new BigDecimal("1000"), 3, RoundingMode.HALF_UP);
      return new Converted(c, v, ConversionStatus.OK,
          "件数×包装规格 " + pack.stripTrailingZeros().toPlainString() + " kg/件 ÷1000");
    }
    return new Converted(c, null, ConversionStatus.UNCONVERTIBLE, "换算规则缺少系数与密度/包装规格声明");
  }

  // ---------------- 事件聚类 ----------------

  /**
   * 按交货事件聚类：同 deliveryNo（缺失时退回 orderNo）为同一笔交付。
   * 同事件内不同单据相互佐证；同类型多份且换算数量不一致构成互斥候选。
   */
  public List<EventBlock> clusterEvents(List<Converted> converted) {
    Map<String, List<Converted>> groups = new LinkedHashMap<>();
    for (Converted cv : converted) {
      EvidenceDocType t = cv.c().docType();
      if (t == EvidenceDocType.RETURN_NOTE || t == EvidenceDocType.PURCHASE_ORDER) continue;
      Candidate c = cv.c();
      String key = c.deliveryNo() != null ? "D:" + c.deliveryNo()
          : (c.orderNo() != null ? "O:" + c.orderNo() : "V:" + c.docNo());
      groups.computeIfAbsent(key, k -> new ArrayList<>()).add(cv);
    }

    List<EventBlock> blocks = new ArrayList<>();
    for (Map.Entry<String, List<Converted>> e : groups.entrySet()) {
      List<Converted> member = e.getValue();
      List<Converted> unconvertible = member.stream()
          .filter(cv -> cv.status() == ConversionStatus.UNCONVERTIBLE).toList();
      Map<BigDecimal, List<Converted>> byValue = new LinkedHashMap<>();
      for (Converted cv : member) {
        if (cv.status() != ConversionStatus.OK) continue;
        byValue.computeIfAbsent(cv.qtyConverted(), k -> new ArrayList<>()).add(cv);
      }
      List<EventOption> options = byValue.entrySet().stream()
          .sorted((a, b) -> Integer.compare(scoreBacking(b.getValue()), scoreBacking(a.getValue())))
          .map(en -> new EventOption(en.getKey(), en.getValue()))
          .toList();
      // 双证（不同单据类型）相互印证的事件视为完整交货，守恒枚举中强制纳入；
      // 仅单一凭证的事件才允许作为“部分交货/未交付”整体排除
      long distinctTypes = member.stream()
          .filter(cv -> cv.status() == ConversionStatus.OK)
          .map(cv -> cv.c().docType()).distinct().count();
      boolean fullyEvidenced = distinctTypes >= 2;
      blocks.add(new EventBlock(e.getKey(),
          member.get(0).c().deliveryNo(), member.get(0).c().orderNo(),
          options, unconvertible, List.of(), fullyEvidenced));
    }
    blocks.sort(Comparator.comparing(EventBlock::eventKey));
    return blocks;
  }

  /** 显式判定规则打分：佐证凭证数多者优先，其次权威性层级（tier 小=高）、已签章、发生时点新者。 */
  private int scoreBacking(List<Converted> backing) {
    int score = backing.size() * 100;
    for (Converted cv : backing) {
      Candidate c = cv.c();
      score += (4 - Math.min(Math.max(c.authorityTier(), 1), 3)) * 10;
      if (c.signed()) score += 5;
      if (c.docDate() != null) score += (int) c.docDate().toEpochDay() % 1000;
    }
    return score;
  }

  /** 组合整体优先级：采用高权威、签章齐全、证据份数多的组合。 */
  public int comboScore(Combo combo, Result result) {
    int score = 0;
    for (Converted cv : result.allConverted()) {
      if (!combo.docIds().contains(cv.c().docId())) continue;
      score += (4 - Math.min(Math.max(cv.c().authorityTier(), 1), 3)) * 10;
      if (cv.c().signed()) score += 5;
    }
    return score;
  }

  // ---------------- 守恒约束与可行组合枚举 ----------------

  public Result run(List<Candidate> candidates, ConstraintContext ctx,
                    Map<String, UnitConversion> rules) {
    List<Converted> converted = candidates.stream()
        .map(c -> convert(c, ctx.targetUnit(), rules)).toList();
    List<EventBlock> events = clusterEvents(converted);
    List<Converted> returns = converted.stream()
        .filter(cv -> cv.c().docType() == EvidenceDocType.RETURN_NOTE
            && cv.status() == ConversionStatus.OK).toList();
    List<Converted> orderDocs = converted.stream()
        .filter(cv -> cv.c().docType() == EvidenceDocType.PURCHASE_ORDER
            && cv.status() == ConversionStatus.OK).toList();
    // 无订单基线时 ordered 保持 null（不能用 0 作为约束，否则全部收货组合都不守恒）
    BigDecimal ordered = ctx.orderedQty() != null ? ctx.orderedQty()
        : (orderDocs.isEmpty() ? null
            : orderDocs.stream().map(Converted::qtyConverted).reduce(BigDecimal.ZERO, BigDecimal::add));
    if (!orderDocs.isEmpty() && !events.isEmpty()) {
      EventBlock first = events.get(0);
      events.set(0, new EventBlock(first.eventKey(), first.deliveryNo(), first.orderNo(),
          first.options(), first.unconvertible(), orderDocs, first.fullyEvidenced()));
    }

    // 每事件选择空间：双证印证事件强制纳入；其余事件可选“不选”（部分交货）+ 各互斥取值
    List<List<EventChoice>> choices = new ArrayList<>();
    long space = 1;
    for (EventBlock block : events) {
      List<EventChoice> cs = new ArrayList<>();
      if (!block.fullyEvidenced()) cs.add(new EventChoice(block, null));
      for (EventOption op : block.options()) cs.add(new EventChoice(block, op));
      space *= cs.size();
      choices.add(cs);
    }
    space *= (1L << Math.min(returns.size(), 20));

    boolean pruned = space > MAX_COMBOS;
    if (pruned) {
      // 组合爆炸剪枝：每事件仅保留显式规则排序第一的取值与“不选”，退货只保留全纳入/全不纳入
      List<List<EventChoice>> trimmed = new ArrayList<>();
      for (List<EventChoice> cs : choices) {
        List<EventChoice> keep = new ArrayList<>();
        keep.add(cs.get(0));
        if (cs.size() > 1) keep.add(cs.get(1));
        trimmed.add(keep);
      }
      choices = trimmed;
    }

    List<Combo> feasible = new ArrayList<>();
    boolean anyUsableEvent = events.stream().anyMatch(b -> !b.options().isEmpty());
    enumerate(choices, 0, new ArrayList<>(), feasible, returns, ordered, anyUsableEvent);
    TreeSet<BigDecimal> totals = new TreeSet<>();
    for (Combo combo : feasible) totals.add(combo.netQty());

    String ruleSummary = "守恒约束：净收货=Σ交货事件取值−Σ退货，且不超过订单量 "
        + (ordered != null ? ordered.toPlainString() + " " + ctx.targetUnit() : "（无订单基线）")
        + "；事件内按 权威性层级→签章→发生时点 排序，互斥取值不可同选，同交货号只计一次防重复入账"
        + (pruned ? "；组合空间超上限已按显式规则剪枝" : "");
    return new Result(feasible, totals, converted, events, returns, pruned, ruleSummary);
  }

  /** 事件选择载体（option 为空表示该事件不纳入——部分交货）。 */
  private record EventChoice(EventBlock block, EventOption option) {}

  private void enumerate(List<List<EventChoice>> choices, int idx, List<EventChoice> picked,
                         List<Combo> feasible, List<Converted> returns, BigDecimal ordered,
                         boolean anyUsableEvent) {
    if (feasible.size() >= MAX_COMBOS) return;
    if (idx == choices.size()) {
      // 退货子集：2^r 种纳入方式，全不纳入始终尝试
      enumerateReturns(picked, returns, 0, new ArrayList<>(), feasible, ordered, anyUsableEvent);
      return;
    }
    for (EventChoice ch : choices.get(idx)) {
      picked.add(ch);
      enumerate(choices, idx + 1, picked, feasible, returns, ordered, anyUsableEvent);
      picked.remove(picked.size() - 1);
    }
  }

  private void enumerateReturns(List<EventChoice> pickedEvents, List<Converted> returns, int idx,
                                List<Converted> pickedReturns, List<Combo> feasible,
                                BigDecimal ordered, boolean anyUsableEvent) {
    if (idx == returns.size()) {
      buildCombo(pickedEvents, pickedReturns, feasible, ordered, anyUsableEvent);
      return;
    }
    enumerateReturns(pickedEvents, returns, idx + 1, pickedReturns, feasible, ordered, anyUsableEvent);
    pickedReturns.add(returns.get(idx));
    enumerateReturns(pickedEvents, returns, idx + 1, pickedReturns, feasible, ordered, anyUsableEvent);
    pickedReturns.remove(pickedReturns.size() - 1);
  }

  private void buildCombo(List<EventChoice> pickedEvents, List<Converted> pickedReturns,
                          List<Combo> feasible, BigDecimal ordered, boolean anyUsableEvent) {
    BigDecimal received = BigDecimal.ZERO;
    Set<Long> docIds = new LinkedHashSet<>();
    List<String> eventKeys = new ArrayList<>();
    Set<String> usedDeliveries = new HashSet<>();
    for (EventChoice ch : pickedEvents) {
      if (ch.option == null) continue;
      // 重复入账防护：同一交货号跨事件只计一次
      if (ch.block.deliveryNo() != null && !usedDeliveries.add(ch.block.deliveryNo())) continue;
      received = received.add(ch.option.value());
      eventKeys.add(ch.block.eventKey());
      ch.option.backing().forEach(cv -> docIds.add(cv.c().docId()));
    }
    // 存在收货凭证时，“所有事件都不选”不是可行解——不能无视全部已接收证据
    if (anyUsableEvent && docIds.isEmpty()) return;
    BigDecimal returned = BigDecimal.ZERO;
    for (Converted r : pickedReturns) {
      // 退货守恒：退货单必须指向已纳入的交货事件（交货号或订单号匹配）
      boolean linked = pickedEvents.stream().anyMatch(ch -> ch.option != null
          && (Objects.equals(ch.block.deliveryNo(), r.c().deliveryNo())
              || (r.c().orderNo() != null && Objects.equals(ch.block.orderNo(), r.c().orderNo()))));
      if (!linked) return;
      returned = returned.add(r.qtyConverted());
      docIds.add(r.c().docId());
    }
    BigDecimal net = received.subtract(returned).setScale(3, RoundingMode.HALF_UP);
    if (net.signum() < 0) return;                          // 退货超过收货 → 不守恒
    if (ordered != null && net.compareTo(ordered.add(EPS)) > 0) return; // 净收货不得超过订单
    Combo combo = new Combo(Collections.unmodifiableSet(docIds), net, List.copyOf(eventKeys));
    boolean dup = feasible.stream().anyMatch(f -> f.netQty().compareTo(net) == 0
        && f.docIds().equals(combo.docIds()));
    if (!dup) feasible.add(combo);
  }

  // ---------------- 证据角色与排除理由 ----------------

  /** 证据角色裁定：采用/排除/支撑。 */
  public Map<Long, EvidenceRole> classify(Result result, Combo adopted) {
    Map<Long, EvidenceRole> roles = new HashMap<>();
    for (Converted cv : result.allConverted()) {
      Candidate c = cv.c();
      if (c.docType() == EvidenceDocType.PURCHASE_ORDER) {
        roles.put(c.docId(), EvidenceRole.SUPPORTING);
      } else if (adopted.docIds().contains(c.docId())) {
        roles.put(c.docId(), EvidenceRole.ADOPTED);
      } else {
        roles.put(c.docId(), EvidenceRole.EXCLUDED);
      }
    }
    return roles;
  }

  /** 排除理由：不可换算 / 互斥落选 / 事件未纳入（部分交货）/ 退货守恒排除 / 订单基线。 */
  public String excludeReason(Converted cv, Result result, Combo adopted) {
    Candidate c = cv.c();
    if (cv.status() == ConversionStatus.UNCONVERTIBLE) return "单位无法换算：" + cv.convertNote();
    if (c.docType() == EvidenceDocType.PURCHASE_ORDER) return "采购订单基线（守恒约束参照，不计入收货量）";
    if (c.docType() == EvidenceDocType.RETURN_NOTE) {
      return adopted.docIds().contains(c.docId())
          ? "退货单已纳入采用组合（冲减对应交货）"
          : "退货未纳入采用组合：无对应已收交货或该组合下守恒不成立";
    }
    boolean inAdoptedEvent = false;
    boolean losingOption = false;
    for (EventBlock b : result.events()) {
      for (EventOption op : b.options()) {
        boolean member = op.backing().stream().anyMatch(x -> x.c().docId().equals(c.docId()));
        if (!member) continue;
        inAdoptedEvent = adopted.eventKeys().contains(b.eventKey());
        boolean optionAdopted = op.backing().stream()
            .allMatch(x -> adopted.docIds().contains(x.c().docId()));
        if (!optionAdopted) losingOption = true;
      }
    }
    if (inAdoptedEvent && losingOption) {
      return "与采用凭证属同一交货事件的互斥候选，按 权威性层级→签章→发生时点 规则落选";
    }
    if (!inAdoptedEvent) return "该交货事件未纳入采用组合（部分交货或订单守恒约束排除）";
    return "未纳入采用组合";
  }
}
