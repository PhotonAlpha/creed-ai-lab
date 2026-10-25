package com.creed.resource.migrationrisk.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** One saved risk item and its review. */
@Entity
@Table(name = "mr_item")
@Getter
@Setter
public class MrItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "analysis_id", nullable = false)
    private Long analysisId;

    /** {@link RiskItem#id()}: the same element and category get the same key in every run. */
    @Column(name = "item_key", nullable = false, length = 1000)
    private String itemKey;

    @Column(nullable = false, length = 64)
    private String fingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private RiskLevel level;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RiskCategory category;

    @Column(nullable = false, length = 1000)
    private String element;

    /** The RiskItem as computed, without its review. */
    @Column(name = "item_json", nullable = false, columnDefinition = "text")
    private String itemJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 16)
    private ReviewStatus reviewStatus = ReviewStatus.PENDING;

    @Column(name = "review_note", length = 2000)
    private String reviewNote;

    @Column(name = "reviewed_by", length = 120)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "carried_over_from")
    private Long carriedOverFrom;

    /** Two reviewers on one item: the second save is a 409, not a silent overwrite. */
    @Version
    private long version;

    public Review review() {
        return new Review(id, reviewStatus, reviewNote, reviewedBy, reviewedAt, carriedOverFrom);
    }
}
