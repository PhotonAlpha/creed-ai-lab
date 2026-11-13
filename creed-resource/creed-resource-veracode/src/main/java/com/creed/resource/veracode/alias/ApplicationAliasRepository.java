package com.creed.resource.veracode.alias;

import com.creed.resource.veracode.domain.ApplicationAlias;
import com.creed.resource.veracode.domain.ApplicationAliases;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/** {@code application_alias} (Flyway {@code V2__application_aliases.sql}). */
@Repository
@RequiredArgsConstructor
class ApplicationAliasRepository {

    private final JdbcClient jdbc;

    List<ApplicationAlias> list() {
        return jdbc.sql("SELECT app_name, alias, repository_url FROM application_alias ORDER BY app_key")
                .query((rs, n) -> new ApplicationAlias(rs.getString("app_name"), rs.getString("alias"),
                        rs.getString("repository_url")))
                .list();
    }

    void insert(ApplicationAlias alias, Instant now) {
        jdbc.sql("""
                        INSERT INTO application_alias (app_key, app_name, alias, repository_url, updated_at)
                        VALUES (:key, :appName, :alias, :url, :now)""")
                .param("key", ApplicationAliases.key(alias.appName())).param("appName", alias.appName())
                .param("alias", alias.alias()).param("url", alias.repositoryUrl())
                .param("now", now.atOffset(ZoneOffset.UTC))
                .update();
    }

    void deleteAll() {
        jdbc.sql("DELETE FROM application_alias").update();
    }
}
