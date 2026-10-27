package com.creed.resource.migrationrisk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Migration sync risk: repo2 is a migration of repo1 (Boot 4 / Jackson 3 / Camel 4 ...), and another team
 * keeps committing to repo1. This compares repo1 HEAD with repo2 HEAD (repo2 may be two repositories, read as one):
 * what differs, where each repo1 element lives in repo2, and how risky syncing it is.
 *
 * <p>One RefactoringMiner run (repo1 -> repo2) finds moved and split code; JDT compares classes member by member,
 * config is compared key by key across all files. Analyses can be saved per release (PostgreSQL / MySQL / H2) for
 * review; the comparison itself is computed from the repositories at request time.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CreedResourceMigrationRiskApplication {

    public static void main(String[] args) {
        SpringApplication.run(CreedResourceMigrationRiskApplication.class, args);
    }
}
