package com.creed.resource.migrationrisk.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MrReleaseRepository extends JpaRepository<MrRelease, Long> {

    Optional<MrRelease> findByName(String name);
}
