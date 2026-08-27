-- Env Matrix Viewer — the declared app-system topology.
--
-- `env_endpoint` records addresses: seven dimensions mapped to a host/ip/port. Nothing in it says
-- "A calls B", and nothing derived from an address ever can — co-location and address clashes are
-- the limit of what a host/port pair knows. This table is therefore the operator-maintained other
-- half: the wiring, declared per tier and edited from the config page.
--
-- Scope is the TIER, not the environment instance. SIT1 and SIT2 are two instances of the same
-- wiring; asking anyone to re-declare an unchanged topology per instance is how it drifts.
--
-- `direction` decides arrowheads only. The stored source -> target orientation is always what the
-- layered view ranks on, so a BIDIRECTIONAL link still says which end is upstream — otherwise every
-- two-way link would be a cycle with no defined layering.

create table env_app_link
(
    id         bigserial primary key,

    tier       varchar(32) not null,
    source_app varchar(64) not null,
    target_app varchar(64) not null,
    direction  varchar(16) not null,

    note       varchar(512),

    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    -- JPA @Version: the config page saves whole rows, same as it does for endpoints.
    version    bigint      not null default 0,

    constraint ck_env_app_link_direction check (direction in ('ONE_WAY', 'BIDIRECTIONAL')),
    -- A self-link is always a data-entry slip and would render as a loop on the node.
    constraint ck_env_app_link_not_self check (source_app <> target_app)
);

-- One declared link per (tier, source, target). A -> B and B -> A are two different rows on purpose:
-- they are only the same fact when direction is BIDIRECTIONAL, and the UI rejects the pair.
create unique index ux_env_app_link_identity
    on env_app_link (tier, source_app, target_app);

create index ix_env_app_link_tier on env_app_link (tier);

-- Seed: the estate's topology as drawn, declared identically for every tier. Systems that have no
-- rows in env_endpoint yet (Global-CCS, Proxy, CEW, GEB, FDR, CCS-FDR) still appear on the graph as
-- placeholders — that gap is exactly what the viewer is meant to make visible.
insert into env_app_link (tier, source_app, target_app, direction, note)
values
    ('SIT', 'Global-CCS', 'CCS', 'BIDIRECTIONAL', 'regional CCS reports up to the global instance'),
    ('SIT', 'CCS', 'CCS-FDR', 'BIDIRECTIONAL', 'CCS exchanges with the FDR front door'),
    ('SIT', 'CCS-FDR', 'FDR', 'BIDIRECTIONAL', 'front door fronts the FDR backend'),
    ('SIT', 'Proxy', 'MS', 'ONE_WAY', 'north-south ingress'),
    ('SIT', 'Proxy', 'CEW', 'ONE_WAY', 'north-south ingress'),
    ('SIT', 'Proxy', 'GEB', 'ONE_WAY', 'north-south ingress'),
    ('SIT', 'CCS', 'MS', 'ONE_WAY', 'east-west service call'),
    ('SIT', 'CCS', 'CEW', 'ONE_WAY', 'east-west service call'),
    ('SIT', 'CCS', 'GEB', 'ONE_WAY', 'east-west service call'),
    ('UAT', 'Global-CCS', 'CCS', 'BIDIRECTIONAL', 'regional CCS reports up to the global instance'),
    ('UAT', 'CCS', 'CCS-FDR', 'BIDIRECTIONAL', 'CCS exchanges with the FDR front door'),
    ('UAT', 'CCS-FDR', 'FDR', 'BIDIRECTIONAL', 'front door fronts the FDR backend'),
    ('UAT', 'Proxy', 'MS', 'ONE_WAY', 'north-south ingress'),
    ('UAT', 'Proxy', 'CEW', 'ONE_WAY', 'north-south ingress'),
    ('UAT', 'Proxy', 'GEB', 'ONE_WAY', 'north-south ingress'),
    ('UAT', 'CCS', 'MS', 'ONE_WAY', 'east-west service call'),
    ('UAT', 'CCS', 'CEW', 'ONE_WAY', 'east-west service call'),
    ('UAT', 'CCS', 'GEB', 'ONE_WAY', 'east-west service call'),
    ('NFT', 'Global-CCS', 'CCS', 'BIDIRECTIONAL', 'regional CCS reports up to the global instance'),
    ('NFT', 'CCS', 'CCS-FDR', 'BIDIRECTIONAL', 'CCS exchanges with the FDR front door'),
    ('NFT', 'CCS-FDR', 'FDR', 'BIDIRECTIONAL', 'front door fronts the FDR backend'),
    ('NFT', 'Proxy', 'MS', 'ONE_WAY', 'north-south ingress'),
    ('NFT', 'Proxy', 'CEW', 'ONE_WAY', 'north-south ingress'),
    ('NFT', 'Proxy', 'GEB', 'ONE_WAY', 'north-south ingress'),
    ('NFT', 'CCS', 'MS', 'ONE_WAY', 'east-west service call'),
    ('NFT', 'CCS', 'CEW', 'ONE_WAY', 'east-west service call'),
    ('NFT', 'CCS', 'GEB', 'ONE_WAY', 'east-west service call'),
    ('PROD', 'Global-CCS', 'CCS', 'BIDIRECTIONAL', 'regional CCS reports up to the global instance'),
    ('PROD', 'CCS', 'CCS-FDR', 'BIDIRECTIONAL', 'CCS exchanges with the FDR front door'),
    ('PROD', 'CCS-FDR', 'FDR', 'BIDIRECTIONAL', 'front door fronts the FDR backend'),
    ('PROD', 'Proxy', 'MS', 'ONE_WAY', 'north-south ingress'),
    ('PROD', 'Proxy', 'CEW', 'ONE_WAY', 'north-south ingress'),
    ('PROD', 'Proxy', 'GEB', 'ONE_WAY', 'north-south ingress'),
    ('PROD', 'CCS', 'MS', 'ONE_WAY', 'east-west service call'),
    ('PROD', 'CCS', 'CEW', 'ONE_WAY', 'east-west service call'),
    ('PROD', 'CCS', 'GEB', 'ONE_WAY', 'east-west service call');
