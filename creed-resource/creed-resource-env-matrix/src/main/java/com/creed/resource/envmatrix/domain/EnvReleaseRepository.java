package com.creed.resource.envmatrix.domain;

import com.creed.resource.envmatrix.api.dto.ReleaseStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EnvReleaseRepository extends JpaRepository<EnvRelease, Long> {

    List<EnvRelease> findAllByOrderByTierAscNameAsc();

    List<EnvRelease> findByTierOrderByNameAsc(String tier);

    List<EnvRelease> findByStatusOrderByTierAscNameAsc(ReleaseStatus status);

    List<EnvRelease> findByTierAndStatusOrderByNameAsc(String tier, ReleaseStatus status);

    /** Name uniqueness guard, so a duplicate answers 409 rather than a raw constraint violation. */
    Optional<EnvRelease> findByName(String name);
}
