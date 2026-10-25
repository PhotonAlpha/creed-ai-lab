package com.creed.resource.migrationrisk.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface MrItemRepository extends JpaRepository<MrItem, Long> {

    List<MrItem> findByAnalysisIdOrderByIdAsc(Long analysisId);

    /** Review progress of one run: [status, count] rows. */
    @Query("select i.reviewStatus, count(i) from MrItem i where i.analysisId = :analysisId group by i.reviewStatus")
    List<Object[]> countByStatus(Long analysisId);
}
