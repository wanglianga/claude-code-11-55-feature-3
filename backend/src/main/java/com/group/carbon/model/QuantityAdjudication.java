package com.group.carbon.model;

import com.group.carbon.common.Types.AdjudicationStatus;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 数量裁定：一个采购凭证号（一笔交付场景）一条生效裁定。
 * PENDING=待裁定/待补（密度或包装规格缺失、组合不唯一且未人工决策）；
 * INVALID=引用的凭证发生变更（重发/作废）后旧裁定失效，保留历史等待重裁。
 */
@Entity
@Table(name = "quantity_adjudications")
public class QuantityAdjudication {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @Column(name = "adj_no", nullable = false, unique = true)
  private String adjNo;
  @Column(nullable = false)
  private String period;
  @Column(name = "plant_id", nullable = false)
  private Long plantId;
  @Column(name = "product_line_id")
  private Long productLineId;
  @Column(name = "energy_type", nullable = false)
  private String energyType;
  @Column(name = "voucher_no", nullable = false)
  private String voucherNo;
  @Column(name = "order_no")
  private String orderNo;
  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private AdjudicationStatus status;
  @Column(name = "adopted_qty")
  private BigDecimal adoptedQty;
  @Column(name = "adopted_unit")
  private String adoptedUnit;
  @Column(name = "decision_rule", length = 1024)
  private String decisionRule;
  @Column(length = 2048)
  private String reason;
  @Column(name = "candidate_count", nullable = false)
  private int candidateCount;
  @Column(nullable = false)
  private boolean pruned;
  @Column(name = "decided_by")
  private String decidedBy;
  @Column(name = "decided_at")
  private LocalDateTime decidedAt;
  @Column(name = "invalidated_reason", length = 1024)
  private String invalidatedReason;
  @Column(name = "invalidated_at")
  private LocalDateTime invalidatedAt;
  @Column(name = "supersedes_adj_id")
  private Long supersedesAdjId;
  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt = LocalDateTime.now();

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getAdjNo() { return adjNo; }
  public void setAdjNo(String adjNo) { this.adjNo = adjNo; }
  public String getPeriod() { return period; }
  public void setPeriod(String period) { this.period = period; }
  public Long getPlantId() { return plantId; }
  public void setPlantId(Long plantId) { this.plantId = plantId; }
  public Long getProductLineId() { return productLineId; }
  public void setProductLineId(Long productLineId) { this.productLineId = productLineId; }
  public String getEnergyType() { return energyType; }
  public void setEnergyType(String energyType) { this.energyType = energyType; }
  public String getVoucherNo() { return voucherNo; }
  public void setVoucherNo(String voucherNo) { this.voucherNo = voucherNo; }
  public String getOrderNo() { return orderNo; }
  public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
  public AdjudicationStatus getStatus() { return status; }
  public void setStatus(AdjudicationStatus status) { this.status = status; }
  public BigDecimal getAdoptedQty() { return adoptedQty; }
  public void setAdoptedQty(BigDecimal adoptedQty) { this.adoptedQty = adoptedQty; }
  public String getAdoptedUnit() { return adoptedUnit; }
  public void setAdoptedUnit(String adoptedUnit) { this.adoptedUnit = adoptedUnit; }
  public String getDecisionRule() { return decisionRule; }
  public void setDecisionRule(String decisionRule) { this.decisionRule = decisionRule; }
  public String getReason() { return reason; }
  public void setReason(String reason) { this.reason = reason; }
  public int getCandidateCount() { return candidateCount; }
  public void setCandidateCount(int candidateCount) { this.candidateCount = candidateCount; }
  public boolean isPruned() { return pruned; }
  public void setPruned(boolean pruned) { this.pruned = pruned; }
  public String getDecidedBy() { return decidedBy; }
  public void setDecidedBy(String decidedBy) { this.decidedBy = decidedBy; }
  public LocalDateTime getDecidedAt() { return decidedAt; }
  public void setDecidedAt(LocalDateTime decidedAt) { this.decidedAt = decidedAt; }
  public String getInvalidatedReason() { return invalidatedReason; }
  public void setInvalidatedReason(String invalidatedReason) { this.invalidatedReason = invalidatedReason; }
  public LocalDateTime getInvalidatedAt() { return invalidatedAt; }
  public void setInvalidatedAt(LocalDateTime invalidatedAt) { this.invalidatedAt = invalidatedAt; }
  public Long getSupersedesAdjId() { return supersedesAdjId; }
  public void setSupersedesAdjId(Long supersedesAdjId) { this.supersedesAdjId = supersedesAdjId; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
