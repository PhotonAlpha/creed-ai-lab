-- Env Matrix Viewer — the endpoint table.
--
-- One row = one endpoint: a seven-dimension identity
-- (app_system, tier, env_instance, country, service, instance, scheme)
-- mapped to a concrete host / ip / port.

create table env_endpoint
(
    id           bigserial primary key,

    -- identity dimensions
    app_system   varchar(64)  not null,
    tier         varchar(32)  not null,
    env_instance varchar(32)  not null,
    country      varchar(16)  not null,
    service      varchar(64)  not null,
    instance     varchar(32)  not null,
    scheme       varchar(8)   not null,

    -- the mapping itself
    host         varchar(255) not null,
    ip           varchar(45)  not null,
    port         integer      not null,

    note         varchar(512),

    created_at   timestamptz  not null default now(),
    updated_at   timestamptz  not null default now(),
    -- JPA @Version: the config page saves whole rows, so a concurrent edit must fail loudly
    -- rather than silently overwrite.
    version      bigint       not null default 0,

    constraint ck_env_endpoint_scheme check (scheme in ('http', 'https')),
    constraint ck_env_endpoint_port check (port between 1 and 65535)
);

-- The identity constraint. Note that `scheme` is part of it: one logical service may legitimately
-- expose both an http and an https endpoint, and those are two rows, not a duplicate.
create unique index ux_env_endpoint_dimensions
    on env_endpoint (app_system, tier, env_instance, country, service, instance, scheme);

-- Conflict detection groups by host:port and ip:port inside a scope bucket (tier/env_instance by
-- default). These three indexes are what keep that scan cheap as the matrix grows.
create index ix_env_endpoint_host_port on env_endpoint (host, port);
create index ix_env_endpoint_ip_port on env_endpoint (ip, port);
create index ix_env_endpoint_scope on env_endpoint (tier, env_instance);

comment on table env_endpoint is 'Environment host/ip/port mapping matrix — one row per endpoint';
comment on column env_endpoint.instance is 'Active-Standby instance label, e.g. Green / Green2';
comment on column env_endpoint.scheme is 'http or https; part of the row identity';
