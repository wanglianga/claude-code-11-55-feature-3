package com.group.carbon.repo;

import com.group.carbon.model.RunAdjudicationBinding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RunAdjudicationBindingRepo extends JpaRepository<RunAdjudicationBinding, Long> {
  List<RunAdjudicationBinding> findByRunIdOrderById(Long runId);
}
