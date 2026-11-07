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
@Table(name = "server_ping_log")
@Entity
@DynamicUpdate
@Data
@EqualsAndHashCode(callSuper = false)
@ToString(exclude = {"serverInfo"})
public class ServerPingLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "status")
    private Boolean status;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "packet_loss")
    private Double packetLoss;

    @Column(name = "check_time")
    private Instant checkTime = Instant.now();

    @NotFound(action = NotFoundAction.IGNORE)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "server_id", referencedColumnName = "id")
    @JsonIgnore
    private ServerInfo serverInfo;

}
