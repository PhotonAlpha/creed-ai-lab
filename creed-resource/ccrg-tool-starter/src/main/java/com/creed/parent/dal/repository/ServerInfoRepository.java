package com.creed.parent.dal.repository;

import com.creed.parent.constant.ServerMetricsType;
import com.creed.parent.dal.entity.ServerInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 9/3/24
 */
@Repository
public interface ServerInfoRepository extends JpaRepository<ServerInfo, Long>, JpaSpecificationExecutor<ServerInfo> {
    Optional<ServerInfo> findByClusterNameAndServerNameAndType(String clusterName, String serverName, ServerMetricsType type);
    Optional<ServerInfo> findByServerNameAndType(String serverName, ServerMetricsType type);

    List<ServerInfo> findByType(ServerMetricsType type);
}
