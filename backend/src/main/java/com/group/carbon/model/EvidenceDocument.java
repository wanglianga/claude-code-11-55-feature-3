package com.group.carbon.model;

import com.group.carbon.common.Types.EvidenceDocStatus;
import com.group.carbon.common.Types.EvidenceDocType;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 接收端凭证登记：保存文件哈希、签章、来源权威性、发生时点以及订单/交货/退货关系。
 * 供应商重发文件必须新建版本：同 doc_no 新记录 version_no 递增，旧版本置 SUPERSEDED。
 */
@Entity
@Table(name = "evidence_documents")
public class EvidenceDocument {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @Column(name = "doc_no", nullable = false)
  private String docNo;
  @Column(name = "version_no", nullable = false)
  private int versionNo = 1;
  @Column(name = "supersedes_id")
  private Long supersedesId;
  @Enumerated(EnumType.STRING)
  @Column(name = "doc_type", nullable = false)
  private EvidenceDocType docType;
  @Column(nullable = false)
  private String supplier;
  @Column(name = "plant_id", nullable = false)
  private Long plantId;
  @Column(nullable = false)
  private String period;
  @Column(name = "voucher_no")
  private String voucherNo;
  @Column(name = "order_no")
  private String orderNo;
  @Column(name = "delivery_no")
  private String deliveryNo;
  @Column(name = "return_no")
  private String returnNo;
  @Column(name = "material_code")
  private String materialCode;
  @Column(nullable = false)
  private boolean signed;
  @Column(name = "authority_tier", nullable = false)
  private int authorityTier;
  @Column(name = "doc_date", nullable = false)
  private LocalDate docDate;
  @Column(name = "received_at", nullable = false)
  private LocalDateTime receivedAt = LocalDateTime.now();
  @Column(name = "file_name", nullable = false)
  private String fileName;
  @Column(name = "file_hash", nullable = false)
  private String fileHash;
  @Column(name = "hash_algorithm", nullable = false)
  private String hashAlgorithm = "SHA-256";
  @Column(name = "file_size")
  private Long fileSize;
  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private EvidenceDocStatus status = EvidenceDocStatus.RECEIVED;
  @Column(name = "void_reason")
  private String voidReason;
  @Column(name = "created_by", nullable = false)
  private String createdBy;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getDocNo() { return docNo; }
  public void setDocNo(String docNo) { this.docNo = docNo; }
  public int getVersionNo() { return versionNo; }
  public void setVersionNo(int versionNo) { this.versionNo = versionNo; }
  public Long getSupersedesId() { return supersedesId; }
  public void setSupersedesId(Long supersedesId) { this.supersedesId = supersedesId; }
  public EvidenceDocType getDocType() { return docType; }
  public void setDocType(EvidenceDocType docType) { this.docType = docType; }
  public String getSupplier() { return supplier; }
  public void setSupplier(String supplier) { this.supplier = supplier; }
  public Long getPlantId() { return plantId; }
  public void setPlantId(Long plantId) { this.plantId = plantId; }
  public String getPeriod() { return period; }
  public void setPeriod(String period) { this.period = period; }
  public String getVoucherNo() { return voucherNo; }
  public void setVoucherNo(String voucherNo) { this.voucherNo = voucherNo; }
  public String getOrderNo() { return orderNo; }
  public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
  public String getDeliveryNo() { return deliveryNo; }
  public void setDeliveryNo(String deliveryNo) { this.deliveryNo = deliveryNo; }
  public String getReturnNo() { return returnNo; }
  public void setReturnNo(String returnNo) { this.returnNo = returnNo; }
  public String getMaterialCode() { return materialCode; }
  public void setMaterialCode(String materialCode) { this.materialCode = materialCode; }
  public boolean isSigned() { return signed; }
  public void setSigned(boolean signed) { this.signed = signed; }
  public int getAuthorityTier() { return authorityTier; }
  public void setAuthorityTier(int authorityTier) { this.authorityTier = authorityTier; }
  public LocalDate getDocDate() { return docDate; }
  public void setDocDate(LocalDate docDate) { this.docDate = docDate; }
  public LocalDateTime getReceivedAt() { return receivedAt; }
  public void setReceivedAt(LocalDateTime receivedAt) { this.receivedAt = receivedAt; }
  public String getFileName() { return fileName; }
  public void setFileName(String fileName) { this.fileName = fileName; }
  public String getFileHash() { return fileHash; }
  public void setFileHash(String fileHash) { this.fileHash = fileHash; }
  public String getHashAlgorithm() { return hashAlgorithm; }
  public void setHashAlgorithm(String hashAlgorithm) { this.hashAlgorithm = hashAlgorithm; }
  public Long getFileSize() { return fileSize; }
  public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
  public EvidenceDocStatus getStatus() { return status; }
  public void setStatus(EvidenceDocStatus status) { this.status = status; }
  public String getVoidReason() { return voidReason; }
  public void setVoidReason(String voidReason) { this.voidReason = voidReason; }
  public String getCreatedBy() { return createdBy; }
  public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
}
