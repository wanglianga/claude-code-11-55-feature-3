package com.group.carbon.repo;

import com.group.carbon.model.UnitConversion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UnitConversionRepo extends JpaRepository<UnitConversion, Long> {
  List<UnitConversion> findByEnergyTypeOrderById(String energyType);
  List<UnitConversion> findByStatusOrderById(String status);
  Optional<UnitConversion> findByEnergyTypeAndFromUnitAndToUnit(String energyType, String fromUnit, String toUnit);
}
