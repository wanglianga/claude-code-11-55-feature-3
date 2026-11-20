package com.group.carbon.model;

import jakarta.persistence.*;
import java.math.BigDecimal;

/** 血缘边：排放结果 ← 来源行（哪份表计/凭证数据支撑了该指标）。 */
@Entity
@Table(name = "emission_lineage")
public class EmissionLineage {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @Column(name = "result_id", nullable = false)
  private Long resultId;
  @Column(name = "source_row_id", nullable = false)
  private Long sourceRowId;
  @Column(name = "qty_used")
  private BigDecimal qtyUsed;
  /** 采购行对应的数量裁定（燃料/电力行为空），用于血缘下钻证据链 */
  @Column(name = "adjudication_id")
  private Long adjudicationId;

  public EmissionLineage() {}
  public EmissionLineage(Long resultId, Long sourceRowId, BigDecimal qtyUsed) {
    this.resultId = resultId;
    this.sourceRowId = sourceRowId;
    this.qtyUsed = qtyUsed;
  }
  public EmissionLineage(Long resultId, Long sourceRowId, BigDecimal qtyUsed, Long adjudicationId) {
    this(resultId, sourceRowId, qtyUsed);
    this.adjudicationId = adjudicationId;
  }

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getResultId() { return resultId; }
  public void setResultId(Long resultId) { this.resultId = resultId; }
  public Long getSourceRowId() { return sourceRowId; }
  public void setSourceRowId(Long sourceRowId) { this.sourceRowId = sourceRowId; }
  public BigDecimal getQtyUsed() { return qtyUsed; }
  public void setQtyUsed(BigDecimal qtyUsed) { this.qtyUsed = qtyUsed; }
  public Long getAdjudicationId() { return adjudicationId; }
  public void setAdjudicationId(Long adjudicationId) { this.adjudicationId = adjudicationId; }
}
