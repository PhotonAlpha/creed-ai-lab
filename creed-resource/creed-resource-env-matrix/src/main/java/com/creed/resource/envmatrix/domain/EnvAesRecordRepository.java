package com.creed.resource.envmatrix.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EnvAesRecordRepository extends JpaRepository<EnvAesRecord, Long> {

    List<EnvAesRecord> findAllByOrderByAppSystemAscPropertyKeyAscHostAscIpAsc();

    List<EnvAesRecord> findByAppSystemOrderByPropertyKeyAscHostAscIpAsc(String appSystem);

    /** Identity lookup: saving the same property for the same server again updates this row. */
    Optional<EnvAesRecord> findByAppSystemAndHostAndIpAndPropertyKey(
            String appSystem, String host, String ip, String propertyKey);
}
