package com.creed.resource.envmatrix.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SplunkAuditRepository extends JpaRepository<SplunkAuditEvent, Long> {

    /** Newest first — the page's audit table. */
    List<SplunkAuditEvent> findAllByOrderByIdDesc(Pageable pageable);

    List<SplunkAuditEvent> findByCorrelationIdOrderByIdAsc(String correlationId);
}
