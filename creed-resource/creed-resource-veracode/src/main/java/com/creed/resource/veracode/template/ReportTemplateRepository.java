package com.creed.resource.veracode.template;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** {@code report_template} (Flyway {@code V1__report_templates.sql}): H2 by default, PostgreSQL with {@code postgres}. */
@Repository
@RequiredArgsConstructor
class ReportTemplateRepository {

    private final JdbcClient jdbc;

    /** @param content the workbook as uploaded; @param mapping {@link TemplateMapping} as JSON */
    record Row(String id, String name, String fileName, byte[] content, String mapping, Instant createdAt,
               Instant updatedAt) {
    }

    /** A row without its workbook, for the list. */
    record Head(String id, String name, String fileName, String mapping, Instant updatedAt) {
    }

    void insert(Row row) {
        jdbc.sql("""
                        INSERT INTO report_template (id, name, file_name, content, mapping, created_at, updated_at)
                        VALUES (:id, :name, :fileName, :content, :mapping, :createdAt, :updatedAt)""")
                .param("id", row.id()).param("name", row.name()).param("fileName", row.fileName())
                .param("content", row.content()).param("mapping", row.mapping())
                .param("createdAt", utc(row.createdAt())).param("updatedAt", utc(row.updatedAt()))
                .update();
    }

    List<Head> list() {
        return jdbc.sql("SELECT id, name, file_name, mapping, updated_at FROM report_template ORDER BY updated_at DESC")
                .query((rs, n) -> new Head(rs.getString("id"), rs.getString("name"), rs.getString("file_name"),
                        rs.getString("mapping"), rs.getObject("updated_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    Optional<Row> find(String id) {
        return jdbc.sql("SELECT * FROM report_template WHERE id = :id").param("id", id)
                .query((rs, n) -> new Row(rs.getString("id"), rs.getString("name"), rs.getString("file_name"),
                        rs.getBytes("content"), rs.getString("mapping"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    boolean updateMapping(String id, String mapping, Instant now) {
        return jdbc.sql("UPDATE report_template SET mapping = :mapping, updated_at = :now WHERE id = :id")
                .param("mapping", mapping).param("now", utc(now)).param("id", id)
                .update() == 1;
    }

    boolean delete(String id) {
        return jdbc.sql("DELETE FROM report_template WHERE id = :id").param("id", id).update() == 1;
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
