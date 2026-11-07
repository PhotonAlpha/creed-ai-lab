package com.creed.parent.dal.repository;

import com.creed.parent.constant.ServerMetricsType;
import com.creed.parent.dal.entity.ServerPingLog;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 9/3/24
 */
@Repository
public interface ServerPingLogRepository extends JpaRepository<ServerPingLog, Long>, JpaSpecificationExecutor<ServerPingLog> {
    List<ServerPingLog> findByServerInfoType(ServerMetricsType type);

    List<ServerPingLog> findByCheckTimeBefore(Instant before, Pageable pageable);

    List<ServerPingLog> findByServerInfoClusterNameAndCheckTimeBetween(String cluster, Instant from, Instant to);

    default List<ServerPingLog> groupByClusterNameAndServerNameCheckTimeBetween(String cluster, Instant from, Instant to) {
        return findAll(
                (Specification<ServerPingLog>) (root, query, cb) -> {

                    Subquery<Long> subquery = query.subquery(Long.class);
                    Root<ServerPingLog> subRoot = subquery.from(ServerPingLog.class);

                    subquery.select(cb.max(subRoot.get("id")));
                    subquery.where(cb.equal(subRoot.get("serverInfo").get("clusterName"), cluster));
                    subquery.groupBy(subRoot.get("serverInfo").get("serverName"));

                    List<Predicate> predicateList = new ArrayList<>();
                    predicateList.add(cb.between(root.get("checkTime"), from, to));
                    predicateList.add(root.get("id").in(subquery));

                    // query.orderBy(cb.desc(subRoot.get("serverInfo").get("serverName")));
                    return cb.and(predicateList.toArray(new Predicate[0]));
                }
        );
    }

}
