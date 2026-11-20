package com.group.carbon.model;

import com.group.carbon.common.Types.FieldExtractStatus;
import jakarta.persistence.*;
import java.math.BigDecimal;

/** 凭证抽取字段：数量、单位、密度、包装规格等按抽取时的原始值冻结。 */
@Entity
@Table(name = "evidence_fields")
public class EvidenceField {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @Column(name = "document_id", nullable = false)
  private Long documentId;
  @Column(name = "field_name", nullable = false)
  private String fieldName;
  @Column(name = "field_text")
  private String fieldText;
  @Column(name = "num_value")
  private BigDecimal numValue;
  private String unit;
  @Enumerated(EnumType.STRING)
  @Column(name = "extract_status", nullable = false)
  private FieldExtractStatus extractStatus = FieldExtractStatus.OK;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getDocumentId() { return documentId; }
  public void setDocumentId(Long documentId) { this.documentId = documentId; }
  public String getFieldName() { return fieldName; }
  public void setFieldName(String fieldName) { this.fieldName = fieldName; }
  public String getFieldText() { return fieldText; }
  public void setFieldText(String fieldText) { this.fieldText = fieldText; }
  public BigDecimal getNumValue() { return numValue; }
  public void setNumValue(BigDecimal numValue) { this.numValue = numValue; }
  public String getUnit() { return unit; }
  public void setUnit(String unit) { this.unit = unit; }
  public FieldExtractStatus getExtractStatus() { return extractStatus; }
  public void setExtractStatus(FieldExtractStatus extractStatus) { this.extractStatus = extractStatus; }
}
