package com.group.carbon.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 单位换算规则：凭证原生单位 → 活动量共同单位。
 * factor 为空且 requires_density/requires_pack_spec 时，必须取得密度/包装规格，否则维持待补。
 */
@Entity
@Table(name = "unit_conversions")
public class UnitConversion {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @Column(name = "energy_type", nullable = false)
  private String energyType;
  @Column(name = "from_unit", nullable = false)
  private String fromUnit;
  @Column(name = "to_unit", nullable = false)
  private String toUnit;
  private BigDecimal factor;
  @Column(name = "requires_density", nullable = false)
  private boolean requiresDensity;
  @Column(name = "requires_pack_spec", nullable = false)
  private boolean requiresPackSpec;
  @Column(name = "default_density")
  private BigDecimal defaultDensity;
  @Column(name = "default_pack_spec")
  private BigDecimal defaultPackSpec;
  @Column(nullable = false)
  private String status = "ACTIVE";
  @Column(name = "created_by", nullable = false)
  private String createdBy;
  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt = LocalDateTime.now();

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getEnergyType() { return energyType; }
  public void setEnergyType(String energyType) { this.energyType = energyType; }
  public String getFromUnit() { return fromUnit; }
  public void setFromUnit(String fromUnit) { this.fromUnit = fromUnit; }
  public String getToUnit() { return toUnit; }
  public void setToUnit(String toUnit) { this.toUnit = toUnit; }
  public BigDecimal getFactor() { return factor; }
  public void setFactor(BigDecimal factor) { this.factor = factor; }
  public boolean isRequiresDensity() { return requiresDensity; }
  public void setRequiresDensity(boolean requiresDensity) { this.requiresDensity = requiresDensity; }
  public boolean isRequiresPackSpec() { return requiresPackSpec; }
  public void setRequiresPackSpec(boolean requiresPackSpec) { this.requiresPackSpec = requiresPackSpec; }
  public BigDecimal getDefaultDensity() { return defaultDensity; }
  public void setDefaultDensity(BigDecimal defaultDensity) { this.defaultDensity = defaultDensity; }
  public BigDecimal getDefaultPackSpec() { return defaultPackSpec; }
  public void setDefaultPackSpec(BigDecimal defaultPackSpec) { this.defaultPackSpec = defaultPackSpec; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public String getCreatedBy() { return createdBy; }
  public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
