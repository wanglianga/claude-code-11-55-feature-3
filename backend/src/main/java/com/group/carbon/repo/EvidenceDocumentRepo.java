package com.group.carbon.repo;

import com.group.carbon.common.Types.EvidenceDocStatus;
import com.group.carbon.model.EvidenceDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EvidenceDocumentRepo extends JpaRepository<EvidenceDocument, Long> {
  List<EvidenceDocument> findByPeriodOrderByIdDesc(String period);
  List<EvidenceDocument> findByVoucherNoOrderByVersionNoDesc(String voucherNo);
  List<EvidenceDocument> findByOrderNoOrderByIdDesc(String orderNo);
  List<EvidenceDocument> findByDeliveryNoOrderByIdDesc(String deliveryNo);
  Optional<EvidenceDocument> findFirstByDocNoOrderByVersionNoDesc(String docNo);
  List<EvidenceDocument> findByDocNoOrderByVersionNoDesc(String docNo);
  List<EvidenceDocument> findByStatusOrderByIdDesc(EvidenceDocStatus status);
}
