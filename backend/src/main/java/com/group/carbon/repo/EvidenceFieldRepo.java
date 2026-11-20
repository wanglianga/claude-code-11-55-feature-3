package com.group.carbon.repo;

import com.group.carbon.model.EvidenceField;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EvidenceFieldRepo extends JpaRepository<EvidenceField, Long> {
  List<EvidenceField> findByDocumentIdOrderById(Long documentId);
  List<EvidenceField> findByDocumentIdInOrderById(List<Long> documentIds);
}
