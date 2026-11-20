package com.group.carbon.service;

import com.group.carbon.dto.Requests.ConversionRuleReq;
import com.group.carbon.dto.Requests.FieldInput;
import com.group.carbon.dto.Requests.ReceiveEvidenceReq;
import com.group.carbon.repo.EvidenceDocumentRepo;
import com.group.carbon.repo.UnitConversionRepo;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 凭证与裁定种子数据：
 * - 单位换算规则：kg→t 固定系数；m3→t 需密度、PCS→t 需包装规格（缺失即待补）；
 * - 2025-05/2025-06 采购凭证号 V-9001/V-9002/V-1001/V-1002 的订单+发票+过磅单三证，
 *   发票与过磅单挂同一交货号且数量一致 → 守恒枚举唯一解，自动裁定采用量与采购批次行一致。
 *
 * 由 SampleDataInitializer 在首次正式运行前显式调用 seedAll()，保证运行能冻结到裁定快照；
 * 自身的 ApplicationRunner 仅做幂等兜底（库内已有凭证时跳过）。
 */
@Component
@Order(20)
public class EvidenceDataInitializer implements ApplicationRunner {
  private final EvidenceService evidenceService;
  private final EvidenceDocumentRepo docRepo;
  private final UnitConversionRepo conversionRepo;

  public EvidenceDataInitializer(EvidenceService evidenceService,
                                 EvidenceDocumentRepo docRepo,
                                 UnitConversionRepo conversionRepo) {
    this.evidenceService = evidenceService;
    this.docRepo = docRepo;
    this.conversionRepo = conversionRepo;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    seedAll();
  }

  @Transactional
  public void seedAll() {
    if (docRepo.count() > 0) return;
    seedConversions();
    // 2025-05：V-9001（订单 500、实收 480，部分交货演示）、V-9002（260 足额）
    seedVoucher("2025-05", 1L, "V-9001", "PO-2025-05-01", "DN-501",
        "INV-501", "WN-501", "PO-501", "500", "480", "480");
    seedVoucher("2025-05", 2L, "V-9002", "PO-2025-05-02", "DN-502",
        "INV-502", "WN-502", "PO-502", "260", "260", "260");
    // 2025-06：V-1001（500t）、V-1002（300t）
    seedVoucher("2025-06", 1L, "V-1001", "PO-2025-06-01", "DN-101",
        "INV-101", "WN-101", "PO-101", "500", "500", "500");
    seedVoucher("2025-06", 2L, "V-1002", "PO-2025-06-02", "DN-102",
        "INV-102", "WN-102", "PO-102", "300", "300", "300");
  }

  private void seedConversions() {
    if (conversionRepo.count() > 0) return;
    evidenceService.saveRule("admin", new ConversionRuleReq(
        "PURCH_STEEL", "kg", "t", new BigDecimal("0.001"),
        false, false, null, null));
    evidenceService.saveRule("admin", new ConversionRuleReq(
        "PURCH_STEEL", "m3", "t", null,
        true, false, null, null));
    evidenceService.saveRule("admin", new ConversionRuleReq(
        "PURCH_STEEL", "PCS", "t", null,
        false, true, null, null));
    evidenceService.saveRule("admin", new ConversionRuleReq(
        "PURCH_ALU", "kg", "t", new BigDecimal("0.001"),
        false, false, null, null));
    evidenceService.saveRule("admin", new ConversionRuleReq(
        "PURCH_ALU", "m3", "t", null,
        true, false, null, null));
  }

  /**
   * 登记一个凭证号的订单/发票/过磅单三证并触发裁定。
   * 发票与过磅单挂同一交货号、数量一致 → 同一事件单一取值 → 唯一可行组合自动裁定。
   */
  private void seedVoucher(String period, Long plantId, String voucherNo, String orderNo,
                           String deliveryNo, String invoiceNo, String weighNo, String poNo,
                           String ordered, String invoiceQty, String weighQty) {
    receive(poNo, "PURCHASE_ORDER", period, plantId, voucherNo, orderNo, null, null,
        3, false, ordered, "t");
    receive(invoiceNo, "INVOICE", period, plantId, voucherNo, orderNo, deliveryNo, null,
        1, true, invoiceQty, "t");
    receive(weighNo, "WEIGHNOTE", period, plantId, voucherNo, orderNo, deliveryNo, null,
        1, true, weighQty, "t");
    evidenceService.adjudicate(period, plantId, voucherNo);
  }

  private void receive(String docNo, String docType, String period, Long plantId, String voucherNo,
                       String orderNo, String deliveryNo, String returnNo, int tier,
                       boolean signed, String qty, String unit) {
    List<FieldInput> fields = new ArrayList<>();
    fields.add(new FieldInput("QUANTITY", qty + unit, new BigDecimal(qty), unit, "OK"));
    fields.add(new FieldInput("UNIT", unit, null, unit, "OK"));
    ReceiveEvidenceReq req = new ReceiveEvidenceReq(
        docNo, docType, "华东钢铁供应商（种子）", plantId, period, voucherNo,
        orderNo, deliveryNo, returnNo, "PURCH_STEEL", signed, tier,
        period + "-15", docNo + ".pdf", sha256(docNo + "|v1"), 20480L, fields);
    evidenceService.receive("analyst", req, false);
  }

  static String sha256(String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
