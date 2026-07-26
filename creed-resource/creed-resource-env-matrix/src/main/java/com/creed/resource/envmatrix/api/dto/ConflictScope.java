package com.creed.resource.envmatrix.api.dto;

import com.creed.resource.envmatrix.domain.EnvEndpoint;

/**
 * Defines "the range in which an address ought to be unique" — the deliberately vague part of the
 * requirement ({@code 在应当唯一的范围内}) made explicit and configurable via
 * {@code env-matrix.conflict.scope}.
 *
 * <p>The default is {@link #TIER_ENV}: inside one concrete environment instance (e.g. {@code UAT/UAT1})
 * a {@code host:port} must belong to exactly one endpoint. Sharing an address *across* environment
 * instances is normal (that is what separate environments are for), so the default does not flag it.
 * Widen to {@link #TIER} or {@link #GLOBAL} when the estate genuinely requires globally distinct
 * addresses.
 */
public enum ConflictScope {

    /** Uniqueness required inside one {@code tier/envInstance} pair. The default. */
    TIER_ENV {
        @Override
        public String keyOf(EnvEndpoint e) {
            return e.getTier() + "/" + e.getEnvInstance();
        }
    },

    /** Uniqueness required across a whole tier, i.e. {@code UAT1}..{@code UAT5} must not overlap. */
    TIER {
        @Override
        public String keyOf(EnvEndpoint e) {
            return e.getTier();
        }
    },

    /** Uniqueness required across the entire estate — every address must be distinct everywhere. */
    GLOBAL {
        @Override
        public String keyOf(EnvEndpoint e) {
            return "*";
        }
    };

    /** The bucket this endpoint belongs to; addresses are only compared within one bucket. */
    public abstract String keyOf(EnvEndpoint e);
}
