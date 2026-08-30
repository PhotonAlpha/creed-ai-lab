package com.creed.resource.envmatrix.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * One participant in a release: an environment slice, identified by
 * {@code (appSystem, country, envInstance)}.
 *
 * <p>It resolves to the endpoints matching that triple — which is what the graph draws inside the
 * participant's group box. There is deliberately <em>no</em> foreign key to {@code env_endpoint}: a
 * participant may name a slice with no endpoints recorded yet, and that gap between "wired into the
 * topology" and "recorded in the matrix" is exactly what the viewer exists to surface.
 *
 * <p>The relationship to {@link EnvRelease} is a plain id column rather than a JPA association,
 * matching the rest of this module — nothing here maps relations, and the service deletes children
 * explicitly so the behaviour does not depend on a foreign key the H2 test schema would not have.
 */
@Entity
@Table(
        name = "env_release_node",
        uniqueConstraints = @UniqueConstraint(
                name = "ux_env_release_node",
                columnNames = {"release_id", "app_system", "country", "env_instance"}))
@Getter
@Setter
@NoArgsConstructor
public class EnvReleaseNode {

    /** Stands in for "not country-specific" — see {@code V4__create_env_release.sql} for why not NULL. */
    public static final String ANY_COUNTRY = "*";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "release_id", nullable = false)
    private Long releaseId;

    @Column(name = "app_system", nullable = false, length = 64)
    private String appSystem;

    /** Country code, or {@link #ANY_COUNTRY} for a slice that is not country-specific. */
    @Column(name = "country", nullable = false, length = 16)
    private String country;

    @Column(name = "env_instance", nullable = false, length = 32)
    private String envInstance;

    /** Optional display name override; the UI falls back to {@code app · country · envInstance}. */
    @Column(name = "label", length = 64)
    private String label;

    /**
     * The layer to draw this participant in, or {@code null} to derive it from the links.
     *
     * <p>Nullable on purpose. The graph ranks participants by a longest path over
     * {@code env_release_link}; a number here overrides that ranking for this participant only and
     * leaves everything downstream of it where the links put it. {@code 0} would not do as the
     * "unset" value — it means "column 0", and a link added later that ought to push the box right
     * would then silently disagree with a number nobody chose.
     */
    @Column(name = "layer")
    private Integer layer;

    /**
     * Position along the cross axis within a layer; {@code 0} is "wherever the default order puts
     * it". Never null, so a release nobody has reordered sorts exactly as it did before the column
     * existed. App systems move as blocks: a cluster takes the lowest sort order of its members.
     */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

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
