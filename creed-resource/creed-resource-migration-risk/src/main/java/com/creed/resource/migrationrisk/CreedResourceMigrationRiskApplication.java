package com.creed.resource.migrationrisk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Migration sync risk: repo2 is a migration of repo1 (Boot 4 / Jackson 3 / Camel 4 ...), and another team
 * keeps committing to repo1. Given the commit the migration started from (the baseline), this says what
 * repo1 changed since then, where each changed element now lives in repo2, and how risky syncing it is.
 *
 * <p>Two RefactoringMiner runs against the same baseline — upstream (repo1@baseline -> repo1@HEAD) and
 * migration (repo1@baseline -> repo2@HEAD) — cross-checked element by element. Stateless: no database,
 * every answer is computed from the repositories at request time.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CreedResourceMigrationRiskApplication {

    public static void main(String[] args) {
        SpringApplication.run(CreedResourceMigrationRiskApplication.class, args);
    }
}
