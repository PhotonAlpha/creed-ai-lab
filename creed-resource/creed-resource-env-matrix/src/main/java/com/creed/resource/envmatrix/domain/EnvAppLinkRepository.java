package com.creed.resource.envmatrix.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface EnvAppLinkRepository extends JpaRepository<EnvAppLink, Long> {

    List<EnvAppLink> findByTierOrderBySourceAppAscTargetAppAsc(String tier);

    List<EnvAppLink> findAllByOrderByTierAscSourceAppAscTargetAppAsc();

    /**
     * Identity guard, checked before insert/update so a duplicate answers 409 with a readable
     * message rather than surfacing a raw constraint violation.
     */
    Optional<EnvAppLink> findByTierAndSourceAppAndTargetApp(String tier, String sourceApp, String targetApp);

    @Query("select distinct l.tier from EnvAppLink l order by l.tier")
    List<String> findDistinctTiers();
}
