-- Env Matrix Viewer — audit trail for the Splunk session broker.
--
-- One row per OTP verification and one per Splunk login call. The two rows of one request share a
-- correlation_id, so "who got this session" is a single lookup. Rows are written in their own
-- transaction as each step finishes, so a failed Splunk call still leaves its OTP row behind.
--
-- The session cookie itself is never stored: cookie_fingerprint is the first 16 hex chars of its
-- SHA-256, enough to match a session seen in Splunk's own logs without the table being a credential.

create table splunk_audit (
    id                 bigserial    primary key,
    correlation_id     varchar(36)  not null,
    -- OTP_VERIFY | SPLUNK_LOGIN
    event_type         varchar(16)  not null,
    -- SUCCESS | FAILURE
    outcome            varchar(8)   not null,
    -- machine-readable cause of a failure: invalid_code, replayed, locked_out, no_session_cookie, …
    reason             varchar(32),
    detail             varchar(512),
    client_ip          varchar(64),
    forwarded_for      varchar(256),
    user_agent         varchar(256),
    -- OTP_VERIFY: the verifier's time step, and the step the code matched (drift = matched - server)
    server_step        bigint,
    matched_step       bigint,
    -- SPLUNK_LOGIN
    splunk_mode        varchar(8),
    http_status        integer,
    cookie_fingerprint varchar(16),
    duration_ms        bigint,
    created_at         timestamptz  not null default now()
);

create index ix_splunk_audit_created_at on splunk_audit (created_at desc);
create index ix_splunk_audit_correlation on splunk_audit (correlation_id);
