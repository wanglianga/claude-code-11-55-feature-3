package com.group.carbon.repo;

import com.group.carbon.common.Types.AdjudicationStatus;
import com.group.carbon.model.QuantityAdjudication;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface QuantityAdjudicationRepo extends JpaRepository<QuantityAdjudication, Long> {
  List<QuantityAdjudication> findByPeriodOrderByIdDesc(String period);
  List<QuantityAdjudication> findByStatusOrderByIdDesc(AdjudicationStatus status);
  List<QuantityAdjudication> findByVoucherNoOrderByIdDesc(String voucherNo);
  Optional<QuantityAdjudication> findFirstByPeriodAndPlantIdAndVoucherNoAndStatusIn(
      String period, Long plantId, String voucherNo, List<AdjudicationStatus> statuses);
  List<QuantityAdjudication> findByPeriodAndStatus(String period, AdjudicationStatus status);
}
