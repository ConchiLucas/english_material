package com.aitaskcenter.repository;

import com.aitaskcenter.model.AgentDefinition;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentDefinitionRepository extends JpaRepository<AgentDefinition, Long> {
    Optional<AgentDefinition> findByAgentKey(String agentKey);
    List<AgentDefinition> findAllByOrderBySortOrderAscNameAsc();
}
