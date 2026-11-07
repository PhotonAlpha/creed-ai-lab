package com.creed.parent.dal.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.creed.parent.constant.ServerMetricsType;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;
import java.util.List;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 9/3/24
 */
@Table(name = "server_info", indexes = {
        @Index(name = "SI_IDX_SERVER_NAME", columnList = "serverName")
})
@Entity
@DynamicUpdate
@Data
@EqualsAndHashCode(callSuper = false)
@ToString(exclude = {"serverPingLogs", "serverMetrics"})
public class ServerInfo {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cluster_name", length = 100)
    private String clusterName;

    @Column(name = "server_name", length = 100)
    private String serverName;

    @Column(name = "server_host", length = 64)
    private String serverHost;
    @Column(name = "server_port")
    private Integer serverPort;

    /*
     * SIT/UAT
     */
    @Column(name = "environment", length = 50)
    private String environment;
    /*
     * sg/hk
     */
    @Column(name = "region", length = 50)
    private String region;
    @Column(name = "type", length = 50)
    @Enumerated(EnumType.STRING)
    private ServerMetricsType type;

    @Column(name = "enabled")
    private Boolean enabled = Boolean.TRUE;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @OneToMany(mappedBy = "serverInfo")
    @JsonIgnore
    private List<ServerPingLog> serverPingLogs;

    @OneToMany(mappedBy = "serverInfo")
    @JsonIgnore
    private List<ServerMetrics> serverMetrics;
}
