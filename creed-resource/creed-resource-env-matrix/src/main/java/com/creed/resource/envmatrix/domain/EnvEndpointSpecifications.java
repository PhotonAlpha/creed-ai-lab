package com.creed.resource.envmatrix.domain;

import com.creed.resource.envmatrix.api.dto.EndpointFilter;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Translates an {@link EndpointFilter} into a JPA {@link Specification}.
 *
 * <p>Each dimension is an {@code IN (...)} over the requested values; an absent or empty list means
 * "don't constrain this dimension". Dimensions are ANDed together, so the UI's filter bar behaves
 * the way a user expects: narrowing one dropdown narrows the result, and clearing it widens it.
 */
public final class EnvEndpointSpecifications {

    private EnvEndpointSpecifications() {
    }

    public static Specification<EnvEndpoint> matching(EndpointFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            in(predicates, root, "appSystem", filter.appSystem());
            in(predicates, root, "tier", filter.tier());
            in(predicates, root, "envInstance", filter.envInstance());
            in(predicates, root, "country", filter.country());
            in(predicates, root, "service", filter.service());
            in(predicates, root, "instance", filter.instance());
            in(predicates, root, "scheme", filter.scheme());
            // Free-text search across the mapping payload — this is how operators hunt for
            // "who else is on 10.20.0.7" or "which rows mention this hostname".
            if (filter.keyword() != null && !filter.keyword().isBlank()) {
                String like = "%" + filter.keyword().trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("host")), like),
                        cb.like(cb.lower(root.get("ip")), like),
                        cb.like(cb.lower(root.get("service")), like),
                        cb.like(cb.lower(root.get("note")), like)));
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static void in(List<Predicate> predicates,
                           jakarta.persistence.criteria.Root<EnvEndpoint> root,
                           String attribute,
                           Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        List<String> cleaned = values.stream().filter(v -> v != null && !v.isBlank()).toList();
        if (!cleaned.isEmpty()) {
            predicates.add(root.get(attribute).in(cleaned));
        }
    }
}
