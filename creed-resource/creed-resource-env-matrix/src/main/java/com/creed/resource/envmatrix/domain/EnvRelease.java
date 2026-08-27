package com.creed.resource.envmatrix.domain;

import com.creed.resource.envmatrix.api.dto.ReleaseStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A named set of environment slices, plus the links between them — the topology graph's scope.
 *
 * <p>This exists because a connection cannot be keyed on app systems. The chain
 * {@code SG CCS SIT3 -> Global-CCS SIT2 -> CN CCS SIT5} has CCS in it twice, so a topology node has
 * to be a slice — {@code (appSystem, country, envInstance)} — and something has to say which slices
 * belong together. That something is the release, which is also what keeps the other dimensions
 * orthogonal: country, envInstance, service and instance stay plain data.
 */
@Entity
@Table(
        name = "env_release",
        uniqueConstraints = @UniqueConstraint(name = "ux_env_release_name", columnNames = "name"))
@Getter
@Setter
@NoArgsConstructor
public class EnvRelease {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Human-facing name, e.g. {@code R2025.09-SIT}. Unique. */
    @Column(name = "name", nullable = false, length = 64)
    private String name;

    /**
     * The tier this release is *about* — a label, not a constraint.
     *
     * <p>Every participant's {@code envInstance} already implies a tier, so this is duplicated
     * information and is deliberately not validated against them: a release whose participants span
     * tiers (a SIT → UAT promotion chain) is legal, and the UI only warns. It is stored because the
     * release list groups and filters on it, and joining out to the participants per row to derive
     * it is not worth the cost.
     */
    @Column(name = "tier", nullable = false, length = 32)
    private String tier;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ReleaseStatus status;

    @Column(name = "note", length = 512)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    void onInsert() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
