package com.creed.resource.envmatrix.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * One release's whole topology, saved in a single transaction.
 *
 * <p>Authoritative for that release and only that release: any participant or link absent from the
 * payload is deleted, and no other release is touched.
 *
 * <p>Participants and links are saved together on purpose. A link cannot exist without its two
 * ends, and the commonest edit is "add a participant and connect it" — with separate routes that
 * needs two round trips and leaves an orphan participant in the database in between.
 */
public record ReleaseTopologyRequest(
        @NotNull @Valid List<Node> nodes,
        @NotNull @Valid List<Link> links) {

    /**
     * A participant. Exactly one of {@code id} (update this row) or {@code ref} (create a new one)
     * is meaningful; {@code ref} is a client-chosen handle that links in the same payload use to
     * point at a participant that does not have a database id yet.
     */
    public record Node(
            Long id,
            @Size(max = 64) String ref,

            @NotBlank @Size(max = 64) String appSystem,
            @NotBlank @Size(max = 16) String country,
            @NotBlank @Size(max = 32) String envInstance,

            @Size(max = 64) String label,
            @Size(max = 512) String note) {
    }

    /** A connection. Both ends are {@link NodeRef}s resolved against {@code nodes}. */
    public record Link(
            Long id,
            @NotNull @Valid NodeRef source,
            @NotNull @Valid NodeRef target,
            @NotNull LinkDirection direction,
            @Size(max = 512) String note) {
    }

    /** Points at an existing participant by {@code id}, or at one created in this payload by {@code ref}. */
    public record NodeRef(Long id, @Size(max = 64) String ref) {

        public boolean isEmpty() {
            return id == null && (ref == null || ref.isBlank());
        }

        /** Stable key for error messages, e.g. {@code id:41} or {@code ref:n1}. */
        public String describe() {
            return id != null ? "id:" + id : "ref:" + ref;
        }
    }
}
