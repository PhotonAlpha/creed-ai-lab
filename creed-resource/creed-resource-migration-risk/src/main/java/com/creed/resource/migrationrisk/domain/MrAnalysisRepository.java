package com.creed.resource.migrationrisk.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MrAnalysisRepository extends JpaRepository<MrAnalysis, Long> {

    /** Newest first. */
    List<MrAnalysis> findByReleaseIdOrderByAnalyzedAtDescIdDesc(Long releaseId);

    Optional<MrAnalysis> findFirstByReleaseIdOrderByAnalyzedAtDescIdDesc(Long releaseId);
}
