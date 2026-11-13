-- Application aliases (asked for, 2026-11-13: was veracode.applications in the configuration): a Veracode app_name
-- under a name of its own and a repository link. app_key is the app_name trimmed and lower-cased - matching ignores
-- case, and H2 has no index on an expression.
CREATE TABLE application_alias (
    app_key        VARCHAR(255)             NOT NULL PRIMARY KEY,
    app_name       VARCHAR(255)             NOT NULL,
    alias          VARCHAR(255),
    repository_url VARCHAR(1000),
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL
);
