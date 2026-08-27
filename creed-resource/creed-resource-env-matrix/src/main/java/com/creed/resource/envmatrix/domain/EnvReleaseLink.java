package com.creed.resource.envmatrix.domain;

import com.creed.resource.envmatrix.api.dto.LinkDirection;
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
 * A declared connection between two {@link EnvReleaseNode participants} — not between two app
 * systems, which is the whole reason this table replaced {@code env_app_link}.
 *
 * <p>{@code releaseId} duplicates what the two nodes already imply. It is kept because the identity
 * index needs it, "every link in this release" is the hottest query, and it is what the service
 * checks both ends against — without it a link could quietly stitch two releases together.
 */
@Entity
@Table(
        name = "env_release_link",
        uniqueConstraints = @UniqueConstraint(
                name = "ux_env_release_link",
                columnNames = {"release_id", "source_node_id", "target_node_id"}))
@Getter
@Setter
@NoArgsConstructor
public class EnvReleaseLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "release_id", nullable = false)
    private Long releaseId;

    @Column(name = "source_node_id", nullable = false)
    private Long sourceNodeId;

    @Column(name = "target_node_id", nullable = false)
    private Long targetNodeId;

    /**
     * Arrowheads only. The stored {@code source -> target} orientation is what the frontend's
     * layered view ranks on, so a {@code BIDIRECTIONAL} link still has a defined upstream end —
     * treating it as an edge both ways would make every such pair a cycle with no layering.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 16)
    private LinkDirection direction;

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
