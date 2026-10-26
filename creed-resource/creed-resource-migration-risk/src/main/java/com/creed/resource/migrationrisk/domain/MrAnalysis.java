package com.creed.resource.migrationrisk.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One saved run. The relationship to its release and items is a plain id column (no JPA association), like
 * env-matrix's release tables: every read here is "this run's rows", never an object graph walk.
 */
@Entity
@Table(name = "mr_analysis")
@Getter
@Setter
public class MrAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "release_id", nullable = false)
    private Long releaseId;

    @Column(nullable = false, length = 16)
    private String mode;

    @Column(name = "repo1_path", nullable = false, length = 1000)
    private String repo1Path;

    @Column(name = "repo2_path", nullable = false, length = 1000)
    private String repo2Path;

    /** The second repo2 (V2); null for a single repo2. */
    @Column(name = "repo2b_path", length = 1000)
    private String repo2bPath;

    /** The baseline as given: a revision (git mode) or the baseline folder (folder mode). */
    @Column(name = "baseline_ref", nullable = false, length = 1000)
    private String baselineRef;

    @Column(name = "total_items", nullable = false)
    private int totalItems;

    @Column(name = "high_items", nullable = false)
    private int highItems;

    @Column(name = "medium_items", nullable = false)
    private int mediumItems;

    @Column(name = "low_items", nullable = false)
    private int lowItems;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "analyzed_at", nullable = false)
    private Instant analyzedAt;

    /** The AnalysisResponse without its items (they are rows of mr_item). */
    @Column(name = "result_json", nullable = false, columnDefinition = "text")
    private String resultJson;
}
