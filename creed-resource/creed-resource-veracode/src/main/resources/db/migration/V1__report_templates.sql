-- Report templates (asked for, 2026-11-12): a company .xlsx as uploaded, and which of its cells show which SCA / SAST
-- field. The JXLS template is derived from both at export, never stored. Runs on PostgreSQL and on H2 in PostgreSQL
-- mode: BYTEA and an unbounded VARCHAR mean the same in both.
CREATE TABLE report_template (
    id         VARCHAR(36)              NOT NULL PRIMARY KEY,
    name       VARCHAR(200)             NOT NULL,
    file_name  VARCHAR(255)             NOT NULL,
    content    BYTEA                    NOT NULL,
    -- TemplateMapping as JSON
    mapping    VARCHAR                  NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
