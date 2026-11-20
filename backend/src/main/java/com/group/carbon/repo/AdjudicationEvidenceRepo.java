package com.group.carbon.repo;

import com.group.carbon.model.AdjudicationEvidence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdjudicationEvidenceRepo extends JpaRepository<AdjudicationEvidence, Long> {
  List<AdjudicationEvidence> findByAdjudicationIdOrderById(Long adjudicationId);
  List<AdjudicationEvidence> findByDocumentId(Long documentId);
}
