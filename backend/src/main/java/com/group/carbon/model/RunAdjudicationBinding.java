package com.group.carbon.model;

import jakarta.persistence.*;
import java.math.BigDecimal;

/**
 * 运行启动时冻结的裁定快照：采用数量、因子口径与证据版本（evidence_chain JSON）。
 * 运行中凭证重发或重裁不影响本运行，与因子绑定快照同语义。
 */
@Entity
@Table(name = "run_adjudication_bindings")
public class RunAdjudicationBinding {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @Column(name = "run_id", nullable = false)
  private Long runId;
  @Column(name = "adjudication_id", nullable = false)
  private Long adjudicationId;
  @Column(name = "adj_no", nullable = false)
  private String adjNo;
  @Column(name = "voucher_no", nullable = false)
  private String voucherNo;
  @Column(name = "plant_id", nullable = false)
  private Long plantId;
  @Column(name = "energy_type", nullable = false)
  private String energyType;
  @Column(name = "adopted_qty", nullable = false)
  private BigDecimal adoptedQty;
  @Column(name = "adopted_unit", nullable = false)
  private String adoptedUnit;
  @Column(name = "decision_rule", length = 1024)
  private String decisionRule;
  @Column(length = 2048)
  private String reason;
  @Column(name = "evidence_chain", nullable = false, columnDefinition = "text")
  private String evidenceChain;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getRunId() { return runId; }
  public void setRunId(Long runId) { this.runId = runId; }
  public Long getAdjudicationId() { return adjudicationId; }
  public void setAdjudicationId(Long adjudicationId) { this.adjudicationId = adjudicationId; }
  public String getAdjNo() { return adjNo; }
  public void setAdjNo(String adjNo) { this.adjNo = adjNo; }
  public String getVoucherNo() { return voucherNo; }
  public void setVoucherNo(String voucherNo) { this.voucherNo = voucherNo; }
  public Long getPlantId() { return plantId; }
  public void setPlantId(Long plantId) { this.plantId = plantId; }
  public String getEnergyType() { return energyType; }
  public void setEnergyType(String energyType) { this.energyType = energyType; }
  public BigDecimal getAdoptedQty() { return adoptedQty; }
  public void setAdoptedQty(BigDecimal adoptedQty) { this.adoptedQty = adoptedQty; }
  public String getAdoptedUnit() { return adoptedUnit; }
  public void setAdoptedUnit(String adoptedUnit) { this.adoptedUnit = adoptedUnit; }
  public String getDecisionRule() { return decisionRule; }
  public void setDecisionRule(String decisionRule) { this.decisionRule = decisionRule; }
  public String getReason() { return reason; }
  public void setReason(String reason) { this.reason = reason; }
  public String getEvidenceChain() { return evidenceChain; }
  public void setEvidenceChain(String evidenceChain) { this.evidenceChain = evidenceChain; }
}
