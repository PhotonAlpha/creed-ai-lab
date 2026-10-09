package com.creed.gatewayproxy.service.splunk.audit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;

import com.creed.gatewayproxy.config.SplunkProperties;

/**
 * The pg and mysql stores — same columns as the Node BFF's audit-store.js, so either process can
 * write a table the other created.
 *
 * <p><b>pg</b> writes {@code <schema>.splunk_audit} (default {@code splunk_broker}), never
 * {@code public.splunk_audit}: that one is Flyway V6 of creed-resource-env-matrix, and creating it
 * first on a fresh database would make V6 fail. On first start, rows of the legacy table are copied
 * once, under an advisory lock so two instances cannot both copy.
 *
 * <p><b>mysql</b> writes {@code splunk_audit} in the URL's database; {@code created_at} is a
 * {@code datetime(3)} written by this store in UTC — a {@code timestamp} would be shifted through the
 * session time zone on every read and write.
 */
@Slf4j
public class JdbcAuditStore implements SplunkAuditStore {

    private final boolean pg;
    private final String table;
    private final String schema;
    private final HikariDataSource dataSource;

    public JdbcAuditStore(SplunkProperties.Audit audit, String password) {
        this.pg = "pg".equals(audit.store());
        this.schema = audit.schema();
        this.table = pg ? audit.schema() + ".splunk_audit" : "splunk_audit";
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("splunk-audit");
        hikari.setJdbcUrl(audit.url());
        hikari.setUsername(audit.username());
        hikari.setPassword(password);
        hikari.setMaximumPoolSize(5);
        hikari.setConnectionTimeout(5000);
        // Connect in init(), so a wrong URL or password fails startup with the store's own message.
        hikari.setInitializationFailTimeout(-1);
        this.dataSource = new HikariDataSource(hikari);
    }

    @Override
    public void init() {
        try (Connection c = dataSource.getConnection()) {
            if (pg) initPg(c);
            else initMysql(c);
        } catch (SQLException e) {
            throw new IllegalStateException("Splunk audit store (" + (pg ? "pg" : "mysql") + ") cannot be initialised: " + e.getMessage(), e);
        }
    }

    private void initPg(Connection c) throws SQLException {
        c.setAutoCommit(false);
        try (Statement st = c.createStatement()) {
            st.execute("select pg_advisory_xact_lock(hashtext('splunk_broker.audit.init'))");
            st.execute("create schema if not exists " + schema);
            st.execute("""
                    create table if not exists %s (
                        id                 bigserial    primary key,
                        correlation_id     varchar(36)  not null,
                        event_type         varchar(16)  not null,
                        outcome            varchar(8)   not null,
                        reason             varchar(32),
                        detail             varchar(512),
                        client_ip          varchar(64),
                        forwarded_for      varchar(256),
                        user_agent         varchar(256),
                        server_step        bigint,
                        matched_step       bigint,
                        splunk_mode        varchar(8),
                        http_status        integer,
                        cookie_fingerprint varchar(16),
                        duration_ms        bigint,
                        created_at         timestamptz  not null default now()
                    )""".formatted(table));
            st.execute("create index if not exists ix_splunk_audit_created_at on " + table + " (created_at desc)");
            st.execute("create index if not exists ix_splunk_audit_correlation on " + table + " (correlation_id)");
            boolean legacy;
            boolean empty;
            try (ResultSet rs = st.executeQuery("select to_regclass('public.splunk_audit') is not null, not exists (select 1 from " + table + ")")) {
                rs.next();
                legacy = rs.getBoolean(1);
                empty = rs.getBoolean(2);
            }
            if (legacy && empty) {
                String cols = "id, " + String.join(", ", SplunkAuditRow.COLUMNS) + ", created_at";
                int copied = st.executeUpdate("insert into " + table + " (" + cols + ") select " + cols + " from public.splunk_audit order by id");
                // Ids were copied verbatim, so the sequence has to move past them.
                st.execute("select setval(pg_get_serial_sequence('" + table + "', 'id'), coalesce(max(id), 0) + 1, false) from " + table);
                if (copied > 0) log.info("copied {} rows from public.splunk_audit into {}", copied, table);
            }
            c.commit();
        } catch (SQLException e) {
            c.rollback();
            throw e;
        }
    }

    private void initMysql(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("""
                    create table if not exists splunk_audit (
                        id                 bigint       not null auto_increment primary key,
                        correlation_id     varchar(36)  not null,
                        event_type         varchar(16)  not null,
                        outcome            varchar(8)   not null,
                        reason             varchar(32),
                        detail             varchar(512),
                        client_ip          varchar(64),
                        forwarded_for      varchar(256),
                        user_agent         varchar(256),
                        server_step        bigint,
                        matched_step       bigint,
                        splunk_mode        varchar(8),
                        http_status        int,
                        cookie_fingerprint varchar(16),
                        duration_ms        bigint,
                        created_at         datetime(3)  not null,
                        index ix_splunk_audit_created_at (created_at),
                        index ix_splunk_audit_correlation (correlation_id)
                    ) engine = InnoDB default charset = utf8mb4""");
        }
    }

    @Override
    public void save(SplunkAuditRow row) {
        String cols = String.join(", ", SplunkAuditRow.COLUMNS) + (pg ? "" : ", created_at");
        int n = SplunkAuditRow.COLUMNS.length + (pg ? 0 : 1);
        String sql = "insert into " + table + " (" + cols + ") values (" + "?, ".repeat(n - 1) + "?)";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            Object[] values = row.values();
            for (int i = 0; i < values.length; i++) {
                if (values[i] == null) ps.setNull(i + 1, Types.NULL);
                else ps.setObject(i + 1, values[i]);
            }
            if (!pg) ps.setObject(n, LocalDateTime.now(ZoneOffset.UTC));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("cannot write the Splunk audit row: " + e.getMessage(), e);
        }
    }

    @Override
    public List<SplunkAuditRow> list(int limit) {
        String sql = "select id, " + String.join(", ", SplunkAuditRow.COLUMNS) + ", created_at from " + table + " order by id desc limit ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, limit);
            List<SplunkAuditRow> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new SplunkAuditRow(rs.getLong("id"), rs.getString("correlation_id"), rs.getString("event_type"),
                            rs.getString("outcome"), rs.getString("reason"), rs.getString("detail"), rs.getString("client_ip"),
                            rs.getString("forwarded_for"), rs.getString("user_agent"), longOrNull(rs, "server_step"),
                            longOrNull(rs, "matched_step"), rs.getString("splunk_mode"), intOrNull(rs, "http_status"),
                            rs.getString("cookie_fingerprint"), longOrNull(rs, "duration_ms"), createdAt(rs)));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read the Splunk audit: " + e.getMessage(), e);
        }
    }

    private Instant createdAt(ResultSet rs) throws SQLException {
        return pg ? rs.getObject("created_at", OffsetDateTime.class).toInstant()
                : rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }

    private static Long longOrNull(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static Integer intOrNull(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
