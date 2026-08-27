package com.creed.resource.envmatrix.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EnvReleaseLinkRepository extends JpaRepository<EnvReleaseLink, Long> {

    List<EnvReleaseLink> findByReleaseIdOrderByIdAsc(Long releaseId);

    void deleteByReleaseId(Long releaseId);
}
