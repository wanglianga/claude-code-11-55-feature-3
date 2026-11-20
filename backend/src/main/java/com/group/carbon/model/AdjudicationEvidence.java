package com.group.carbon.model;

import com.group.carbon.common.Types.ConversionStatus;
import com.group.carbon.common.Types.EvidenceDocType;
import com.group.carbon.common.Types.EvidenceRole;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 裁定证据条目：每份候选凭证在裁定中的角色（采用/排除/支撑）、换算结果与排除理由。
 * doc_hash 冻结引用时刻的文件哈希；exclusive_group 标记相互排斥的候选组。
 */
@Entity
@Table(name = "adjudication_evidence")
public class AdjudicationEvidence {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @Column(name = "adjudication_id", nullable = false)
  private Long adjudicationId;
  @Column(name = "document_id", nullable = false)
  private Long documentId;
  @Column(name = "doc_no", nullable = false)
  private String docNo;
  @Column(name = "doc_version_no", nullable = false)
  private int docVersionNo;
  @Enumerated(EnumType.STRING)
  @Column(name = "doc_type", nullable = false)
  private EvidenceDocType docType;
  @Column(name = "doc_hash", nullable = false, length = 128)
  private String docHash;
  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private EvidenceRole role;
  @Column(name = "exclusive_group")
  private String exclusiveGroup;
  @Column(name = "qty_native")
  private BigDecimal qtyNative;
  @Column(name = "unit_native")
  private String unitNative;
  @Column(name = "qty_converted")
  private BigDecimal qtyConverted;
  @Enumerated(EnumType.STRING)
  @Column(name = "conversion_status", nullable = false)
  private ConversionStatus conversionStatus;
  @Column(name = "exclude_reason", length = 512)
  private String excludeReason;
  @Column(name = "doc_signed", nullable = false)
  private boolean docSigned;
  @Column(name = "authority_tier", nullable = false)
  private int authorityTier;
  @Column(name = "doc_date")
  private LocalDate docDate;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getAdjudicationId() { return adjudicationId; }
  public void setAdjudicationId(Long adjudicationId) { this.adjudicationId = adjudicationId; }
  public Long getDocumentId() { return documentId; }
  public void setDocumentId(Long documentId) { this.documentId = documentId; }
  public String getDocNo() { return docNo; }
  public void setDocNo(String docNo) { this.docNo = docNo; }
  public int getDocVersionNo() { return docVersionNo; }
  public void setDocVersionNo(int docVersionNo) { this.docVersionNo = docVersionNo; }
  public EvidenceDocType getDocType() { return docType; }
  public void setDocType(EvidenceDocType docType) { this.docType = docType; }
  public String getDocHash() { return docHash; }
  public void setDocHash(String docHash) { this.docHash = docHash; }
  public EvidenceRole getRole() { return role; }
  public void setRole(EvidenceRole role) { this.role = role; }
  public String getExclusiveGroup() { return exclusiveGroup; }
  public void setExclusiveGroup(String exclusiveGroup) { this.exclusiveGroup = exclusiveGroup; }
  public BigDecimal getQtyNative() { return qtyNative; }
  public void setQtyNative(BigDecimal qtyNative) { this.qtyNative = qtyNative; }
  public String getUnitNative() { return unitNative; }
  public void setUnitNative(String unitNative) { this.unitNative = unitNative; }
  public BigDecimal getQtyConverted() { return qtyConverted; }
  public void setQtyConverted(BigDecimal qtyConverted) { this.qtyConverted = qtyConverted; }
  public ConversionStatus getConversionStatus() { return conversionStatus; }
  public void setConversionStatus(ConversionStatus conversionStatus) { this.conversionStatus = conversionStatus; }
  public String getExcludeReason() { return excludeReason; }
  public void setExcludeReason(String excludeReason) { this.excludeReason = excludeReason; }
  public boolean isDocSigned() { return docSigned; }
  public void setDocSigned(boolean docSigned) { this.docSigned = docSigned; }
  public int getAuthorityTier() { return authorityTier; }
  public void setAuthorityTier(int authorityTier) { this.authorityTier = authorityTier; }
  public LocalDate getDocDate() { return docDate; }
  public void setDocDate(LocalDate docDate) { this.docDate = docDate; }
}
