package com.creed.resource.veracode.domain;

/**
 * A Veracode application under a name of its own (asked for, 2026-11-10; edited on the report-templates page since
 * 2026-11-13, was {@code veracode.applications}): {@code GBT-T1-Creed-CCS} shown as {@code Creed-CCS}, linking to its
 * repository.
 *
 * @param appName       the report's {@code app_name}, matched case-insensitively
 * @param alias         shown on the page, in the Jira description and the Confluence export, and the application's Jira
 *                      label; blank = the {@code app_name}
 * @param repositoryUrl where the alias links to; blank = no link
 */
public record ApplicationAlias(String appName, String alias, String repositoryUrl) {
}
