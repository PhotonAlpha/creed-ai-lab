package com.creed.resource.envmatrix.domain;

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
import com.creed.resource.envmatrix.api.dto.LinkDirection;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A declared connection between two <em>app systems</em> — the topology graph's edges.
 *
 * <p>This table exists because {@code env_endpoint} cannot answer the question. It records
 * addresses: seven dimensions mapped to a host/ip/port. Nothing in it says "A calls B", and no
 * amount of derivation from addresses can invent that — co-location and address clashes are all a
 * host/port can tell you. So the relationship is its own, operator-maintained fact.
 *
 * <p><strong>Scope is the tier</strong>, not the environment instance: SIT1 and SIT2 are two
 * instances of the same wiring, and asking someone to re-declare an unchanged topology per instance
 * is how it drifts. The topology page therefore requires a tier and leaves the instance optional.
 *
 * <p>{@link #direction} decides the arrowheads only. The stored {@code sourceApp -> targetApp}
 * orientation is always what the layered view ranks on, so a {@code BIDIRECTIONAL} link still says
 * which end is upstream — otherwise every two-way link would be a cycle with no defined layering.
 */
@Entity
@Table(
        name = "env_app_link",
        // Mirrors ux_env_app_link_identity from V3__create_env_app_link.sql, so the ddl-auto schema
        // the tests run against enforces the same identity as production.
        uniqueConstraints = @UniqueConstraint(
                name = "ux_env_app_link_identity",
                columnNames = {"tier", "source_app", "target_app"}))
@Getter
@Setter
@NoArgsConstructor
public class EnvAppLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Environment tier this wiring applies to, e.g. {@code SIT} / {@code UAT} / {@code NFT} / {@code PROD}. */
    @Column(name = "tier", nullable = false, length = 32)
    private String tier;

    /** Upstream app system, matched by name against {@code env_endpoint.app_system}. */
    @Column(name = "source_app", nullable = false, length = 64)
    private String sourceApp;

    /** Downstream app system. */
    @Column(name = "target_app", nullable = false, length = 64)
    private String targetApp;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 16)
    private LinkDirection direction;

    /** Free-form operator note — what this connection actually carries. */
    @Column(name = "note", length = 512)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Optimistic lock — the config page saves whole rows, same as it does for endpoints. */
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
