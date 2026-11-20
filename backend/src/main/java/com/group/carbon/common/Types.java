package com.group.carbon.common;

/** 平台全部业务枚举，集中定义避免散落。 */
public final class Types {
  private Types() {}

  public enum Role { ADMIN, ANALYST, AUDITOR, FINANCE }

  /** 批次状态：已上传 / 已核验 / 已驳回 / 冲突 / 被取代 / 已作废 */
  public enum BatchStatus { UPLOADED, VERIFIED, REJECTED, CONFLICT, SUPERSEDED, VOID }

  public enum RowStatus { ACTIVE, SUPERSEDED, VOID }

  /** 因子版本状态：草稿 / 已批准 / 被替换 / 已停用 */
  public enum FactorStatus { DRAFT, APPROVED, REPLACED, RETIRED }

  public enum RunStatus { RUNNING, COMPLETED, FAILED }

  /** OFFICIAL=正式运行；CANDIDATE=影响评估产生的候选运行 */
  public enum RunPurpose { OFFICIAL, CANDIDATE }

  /** OK=正常；PENDING_FACTOR=缺少适用批准因子；PENDING_EVIDENCE=采购数量未裁定/待补，指标待核 */
  public enum ResultStatus { OK, PENDING_FACTOR, PENDING_EVIDENCE }

  /** RECONCILE_FAILED 即“待核”状态：勾稽不平或存在待核指标 */
  public enum ReportStatus { DRAFT, PENDING_REVIEW, RECONCILE_FAILED, APPROVED, PUBLISHED }

  public enum VersionStatus { FROZEN, PUBLISHED, SUPERSEDED, RESTATED }

  public enum ReconcileStatus { OK, MISMATCH, PENDING_FACTOR, PENDING_EVIDENCE }

  public enum TriggerType { SOURCE_SUPPLEMENT, SOURCE_REVISION, SOURCE_MISSING, FACTOR_REVISION }

  public enum ImpactStatus { OPEN, ACKNOWLEDGED, RESOLVED }

  public enum RestatementStatus { PROPOSED, ACCEPTED, REJECTED, RESOLVED }

  /** 凭证类型：发票 / 过磅单 / 供应商环境声明 / 送货单 / 退货单 / 采购订单 */
  public enum EvidenceDocType { INVOICE, WEIGHNOTE, ENV_DECLARATION, DELIVERY_NOTE, RETURN_NOTE, PURCHASE_ORDER }

  public enum EvidenceDocStatus { RECEIVED, SUPERSEDED, VOID }

  public enum FieldExtractStatus { OK, MISSING, UNREADABLE }

  /** 凭证字段换算到共同单位的结果：可换算 / 不可换算（密度或包装规格缺失，维持待补） */
  public enum ConversionStatus { OK, UNCONVERTIBLE }

  /** 数量裁定状态：待裁定（含待补） / 已裁定 / 已失效（引用凭证变更） */
  public enum AdjudicationStatus { PENDING, DECIDED, INVALID }

  /** 证据在裁定中的角色：采用 / 排除（互斥或不守恒） / 支撑 */
  public enum EvidenceRole { ADOPTED, EXCLUDED, SUPPORTING }
}
