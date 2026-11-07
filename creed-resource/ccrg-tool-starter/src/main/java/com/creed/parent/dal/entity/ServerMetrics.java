package com.creed.parent.dal.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;

import java.time.Instant;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 9/3/24
 */
@Table(name = "server_metrics")
@Entity
@DynamicUpdate
@Data
@EqualsAndHashCode(callSuper = false)
@ToString(exclude = {"serverInfo"})
public class ServerMetrics {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "total_memory")
    private Long totalMemory;

    @Column(name = "used_memory")
    private Long usedMemory;

    @Column(name = "free_memory")
    private Long freeMemory;

    @Column(name = "cpu_usage")
    private Double cpuUsage;

    @Column(name = "thread_count")
    private Long threadCount;

    @Column(name = "deamon_thread_count")
    private Long deamonThreadCount;

    @Column(name = "heap_used")
    private Long heapUsed;

    @Column(name = "heap_max")
    private Long heapMax;

    @Column(name = "status")
    private Boolean status = Boolean.TRUE;

    @Column(name = "check_time")
    private Instant checkTime = Instant.now();

    @NotFound(action = NotFoundAction.IGNORE)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "server_id", referencedColumnName = "id")
    @JsonIgnore
    private ServerInfo serverInfo;
}
