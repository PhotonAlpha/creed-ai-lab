package com.creed.resource.envmatrix.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A single {@code endpoint} — the atomic unit of the matrix.
 *
 * <p>Identity is the seven-dimension tuple
 * {@code (appSystem, tier, envInstance, country, service, instance, scheme)}; the database enforces
 * it with a unique index (see {@code V1__create_env_endpoint.sql}). The payload is the actual
 * {@code host} / {@code ip} / {@code port} mapping that the tuple resolves to.
 *
 * <p>Dimension values are stored as plain text rather than as enums on purpose: the requirement
 * outline expects new values (extra countries, extra {@code UATn} instances, new app systems) to be
 * added as data, without a code change or migration. {@code GET /api/env-matrix/dimensions} derives
 * the UI's filter options from the distinct values actually present, so the option lists extend
 * themselves as rows are inserted.
 */
@Entity
@Table(
        name = "env_endpoint",
        // Mirrors ux_env_endpoint_dimensions from V1__create_env_endpoint.sql. Declared here as well
        // so the ddl-auto schema the tests run against enforces the same identity as production.
        uniqueConstraints = @jakarta.persistence.UniqueConstraint(
                name = "ux_env_endpoint_dimensions",
                columnNames = {"app_system", "tier", "env_instance", "country", "service", "instance", "scheme"}))
@Getter
@Setter
@NoArgsConstructor
public class EnvEndpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** App system, e.g. {@code CCS} / {@code MS} / {@code AliYunTeir} / {@code TencentTeir}. */
    @Column(name = "app_system", nullable = false, length = 64)
    private String appSystem;

    /** Environment tier, e.g. {@code SIT} / {@code UAT} / {@code NFT} / {@code PROD}. */
    @Column(name = "tier", nullable = false, length = 32)
    private String tier;

    /** Concrete environment instance within the tier, e.g. {@code UAT1}..{@code UAT5}. */
    @Column(name = "env_instance", nullable = false, length = 32)
    private String envInstance;

    /** Country / region code, e.g. {@code CN} / {@code SG} / {@code MY} / {@code HK} / {@code GD} / {@code ID}. */
    @Column(name = "country", nullable = false, length = 16)
    private String country;

    /** Service name, e.g. {@code MS1}..{@code MS6} / {@code CCS1}..{@code CCS6}. */
    @Column(name = "service", nullable = false, length = 64)
    private String service;

    /** Active-Standby instance label, e.g. {@code Green} / {@code Green2} / {@code Green3}. */
    @Column(name = "instance", nullable = false, length = 32)
    private String instance;

    /** Transport scheme — {@code http} or {@code https}. Drives the UI's scheme filter. */
    @Column(name = "scheme", nullable = false, length = 8)
    private String scheme;

    @Column(name = "host", nullable = false, length = 255)
    private String host;

    @Column(name = "ip", nullable = false, length = 45)
    private String ip;

    @Column(name = "port", nullable = false)
    private Integer port;

    /** Free-form operator note; surfaced in the config table but never used for conflict logic. */
    @Column(name = "note", length = 512)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Optimistic lock — the config page saves whole rows, so concurrent edits must not silently win. */
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

    /** {@code host:port} — one of the two keys conflict detection groups on. */
    public String hostPort() {
        return host + ":" + port;
    }

    /** {@code ip:port} — the other conflict key; catches two hostnames pointing at one address. */
    public String ipPort() {
        return ip + ":" + port;
    }
}
