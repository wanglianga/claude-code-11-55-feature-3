package com.group.carbon.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/** API 请求体定义。 */
public final class Requests {
  private Requests() {}

  public record LoginReq(@NotBlank(message = "用户名不能为空") String username,
                         @NotBlank(message = "密码不能为空") String password) {}

  /** plantId 为空时取批次工厂；集团采购批次可按行归属不同工厂 */
  public record RowInput(Long plantId,
                         Long productLineId,
                         @NotBlank(message = "能源类型不能为空") String energyType,
                         @NotNull(message = "数量不能为空") BigDecimal quantity,
                         String voucherNo) {}

  public record CreateBatchReq(@NotNull(message = "工厂不能为空") Long plantId,
                               @NotBlank(message = "批次类别不能为空") String category,
                               @NotBlank(message = "期间不能为空") String period,
                               String batchNo,
                               @NotEmpty(message = "批次至少包含一行数据") List<RowInput> rows) {}

  public record VoidReq(@NotBlank(message = "作废原因不能为空") String reason) {}

  public record CreateFactorReq(@NotBlank(message = "因子编码不能为空") String code,
                                @NotBlank(message = "因子名称不能为空") String name,
                                @NotBlank(message = "能源类型不能为空") String energyType,
                                @NotBlank(message = "地域不能为空") String region) {}

  public record CreateVersionReq(@NotNull(message = "因子值不能为空") BigDecimal value,
                                 @NotBlank(message = "适用期起不能为空") String validFrom,
                                 String validTo) {}

  public record CreateRunReq(@NotBlank(message = "期间不能为空") String period) {}

  public record CreateReportReq(@NotBlank(message = "报告标题不能为空") String title,
                                @NotBlank(message = "期间不能为空") String period,
                                @NotNull(message = "必须选择核算运行") Long runId) {}

  public record FreezeReq(@NotNull(message = "必须选择核算运行") Long runId, String note) {}

  public record ReasonReq(String reason) {}

  /** 凭证字段抽取结果（接收端登记时提交） */
  public record FieldInput(@NotBlank(message = "字段名不能为空") String name,
                           String text,
                           java.math.BigDecimal numValue,
                           String unit,
                           String extractStatus) {}

  /** 接收端凭证登记：文件哈希、签章、权威性、订单/交货/退货关系与抽取字段 */
  public record ReceiveEvidenceReq(@NotBlank(message = "单据编号不能为空") String docNo,
                                   @NotBlank(message = "单据类型不能为空") String docType,
                                   @NotBlank(message = "供应商不能为空") String supplier,
                                   @NotNull(message = "工厂不能为空") Long plantId,
                                   @NotBlank(message = "期间不能为空") String period,
                                   String voucherNo,
                                   String orderNo,
                                   String deliveryNo,
                                   String returnNo,
                                   String materialCode,
                                   Boolean signed,
                                   Integer authorityTier,
                                   @NotBlank(message = "单据日期不能为空") String docDate,
                                   @NotBlank(message = "文件名不能为空") String fileName,
                                   @NotBlank(message = "文件哈希不能为空") String fileHash,
                                   Long fileSize,
                                   @NotEmpty(message = "至少登记一个抽取字段") List<FieldInput> fields) {}

  /** 单位换算规则维护 */
  public record ConversionRuleReq(@NotBlank String energyType,
                                  @NotBlank String fromUnit,
                                  @NotBlank String toUnit,
                                  java.math.BigDecimal factor,
                                  Boolean requiresDensity,
                                  Boolean requiresPackSpec,
                                  java.math.BigDecimal defaultDensity,
                                  java.math.BigDecimal defaultPackSpec) {}

  /** 人工裁定：从可行候选中选定采用数量并记录理由（不能用金额/平均数填活动量） */
  public record DecideAdjudicationReq(@NotNull(message = "必须指定采用数量候选") java.math.BigDecimal adoptedQty,
                                      @NotBlank(message = "裁定理由不能为空") String reason) {}
}
