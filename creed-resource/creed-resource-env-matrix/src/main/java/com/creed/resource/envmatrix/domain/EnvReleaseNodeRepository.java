package com.creed.resource.envmatrix.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EnvReleaseNodeRepository extends JpaRepository<EnvReleaseNode, Long> {

    List<EnvReleaseNode> findByReleaseIdOrderByAppSystemAscCountryAscEnvInstanceAsc(Long releaseId);

    void deleteByReleaseId(Long releaseId);
}
