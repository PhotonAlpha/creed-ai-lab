package com.creed.parent.dal.repository;

import com.creed.parent.dal.entity.ServerInfo;
import com.creed.parent.dal.entity.ServerMetrics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 9/3/24
 */
@Repository
public interface ServerMetricsRepository extends JpaRepository<ServerMetrics, Long>, JpaSpecificationExecutor<ServerInfo> {
    List<ServerMetrics> findByServerInfoServerName(String name);

    List<ServerMetrics> findByServerInfoServerNameAndCheckTimeBetween(String name, Instant from, Instant to);

    List<ServerMetrics> findByCheckTimeBefore(Instant before);
}
