-- Env Matrix Viewer — the release topology.
--
-- Supersedes env_app_link, which keyed a connection on (tier, sourceApp, targetApp) and therefore
-- could not hold a chain where one app system appears twice:
--
--     SG CCS SIT3  ->  Global-CCS SIT2  ->  CN CCS SIT5
--
-- CCS is two different participants there. So a topology node is not an app system, it is an
-- environment slice: (app_system, country, env_instance). A RELEASE names a set of those slices and
-- the links between them, which is also what lets the other dimensions stay orthogonal — country,
-- env_instance, service and instance are plain data, and the release is the only thing that ties
-- particular values of them together into a graph.
--
-- The old table is dropped rather than migrated: its rows carry no country or env_instance, so
-- nothing can infer which slice each one meant.

drop table if exists env_app_link;

create table env_release
(
    id         bigserial primary key,

    name       varchar(64)  not null,
    -- A label, deliberately NOT enforced against the participants. Every participant's env_instance
    -- already implies a tier, so storing it again could drift; but the release list groups and
    -- filters by tier and joining out to the participants for every row is not worth it. A release
    -- whose participants span tiers (a SIT -> UAT promotion chain) is legal; the UI warns.
    tier       varchar(32)  not null,
    status     varchar(16)  not null,
    note       varchar(512),

    created_at timestamptz  not null default now(),
    updated_at timestamptz  not null default now(),
    version    bigint       not null default 0,

    constraint ck_env_release_status check (status in ('DRAFT', 'ACTIVE', 'ARCHIVED'))
);

create unique index ux_env_release_name on env_release (name);

-- One participant = one environment slice. Resolves to the endpoints matching the triple, which is
-- what the graph draws inside the group box.
create table env_release_node
(
    id           bigserial primary key,
    release_id   bigint      not null references env_release (id) on delete cascade,

    app_system   varchar(64) not null,
    -- '*' means "not country-specific" (Global-CCS). A NULL would be more honest, but Postgres lets
    -- several NULLs through a unique index, so the identity would need a coalesce() expression
    -- index — and JPA's @UniqueConstraint cannot express one, which would leave the H2 schema the
    -- tests run against without the constraint. '*' cannot collide with a real country code.
    country      varchar(16) not null default '*',
    env_instance varchar(32) not null,

    label        varchar(64),
    note         varchar(512),

    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),
    version      bigint      not null default 0
);

create unique index ux_env_release_node
    on env_release_node (release_id, app_system, country, env_instance);
create index ix_env_release_node_release on env_release_node (release_id);

-- A link joins two participants, not two app systems.
create table env_release_link
(
    id             bigserial primary key,
    -- Derivable from the two nodes, but kept: the uniqueness rule needs it, "every link in this
    -- release" is the hottest query, and it is what the service checks both ends against so a link
    -- can never stitch two releases together.
    release_id     bigint      not null references env_release (id) on delete cascade,
    source_node_id bigint      not null references env_release_node (id) on delete cascade,
    target_node_id bigint      not null references env_release_node (id) on delete cascade,

    -- Arrowheads only. The stored source -> target orientation is what the layered view ranks on,
    -- so a two-way link still has a defined upstream end.
    direction      varchar(16) not null,
    note           varchar(512),

    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now(),
    version        bigint      not null default 0,

    constraint ck_env_release_link_direction check (direction in ('ONE_WAY', 'BIDIRECTIONAL')),
    constraint ck_env_release_link_not_self check (source_node_id <> target_node_id)
);

create unique index ux_env_release_link
    on env_release_link (release_id, source_node_id, target_node_id);
create index ix_env_release_link_release on env_release_link (release_id);

-- ---------------------------------------------------------------------------------------------
-- Seed A — one baseline release per tier, reproducing the estate topology as drawn. Participants
-- are country-agnostic ('*') and sit in the tier's first environment instance, so upgrading from
-- env_app_link leaves the graph looking the same.

insert into env_release (id, name, tier, status, note)
values
    (1, 'BASELINE-SIT', 'SIT', 'ACTIVE', 'estate topology baseline for SIT'),
    (2, 'BASELINE-UAT', 'UAT', 'ACTIVE', 'estate topology baseline for UAT'),
    (3, 'BASELINE-NFT', 'NFT', 'ACTIVE', 'estate topology baseline for NFT'),
    (4, 'BASELINE-PROD', 'PROD', 'ACTIVE', 'estate topology baseline for PROD'),
    (5, 'R2025.09-SIT', 'SIT', 'ACTIVE', 'cross-country example: one app system appearing twice in a single chain');

insert into env_release_node (id, release_id, app_system, country, env_instance, note)
values
    (1, 1, 'Global-CCS', '*', 'SIT1', null),
    (2, 1, 'Proxy', '*', 'SIT1', null),
    (3, 1, 'CCS', '*', 'SIT1', null),
    (4, 1, 'CCS-FDR', '*', 'SIT1', null),
    (5, 1, 'MS', '*', 'SIT1', null),
    (6, 1, 'CEW', '*', 'SIT1', null),
    (7, 1, 'GEB', '*', 'SIT1', null),
    (8, 1, 'FDR', '*', 'SIT1', null),
    (9, 2, 'Global-CCS', '*', 'UAT1', null),
    (10, 2, 'Proxy', '*', 'UAT1', null),
    (11, 2, 'CCS', '*', 'UAT1', null),
    (12, 2, 'CCS-FDR', '*', 'UAT1', null),
    (13, 2, 'MS', '*', 'UAT1', null),
    (14, 2, 'CEW', '*', 'UAT1', null),
    (15, 2, 'GEB', '*', 'UAT1', null),
    (16, 2, 'FDR', '*', 'UAT1', null),
    (17, 3, 'Global-CCS', '*', 'NFT1', null),
    (18, 3, 'Proxy', '*', 'NFT1', null),
    (19, 3, 'CCS', '*', 'NFT1', null),
    (20, 3, 'CCS-FDR', '*', 'NFT1', null),
    (21, 3, 'MS', '*', 'NFT1', null),
    (22, 3, 'CEW', '*', 'NFT1', null),
    (23, 3, 'GEB', '*', 'NFT1', null),
    (24, 3, 'FDR', '*', 'NFT1', null),
    (25, 4, 'Global-CCS', '*', 'PROD1', null),
    (26, 4, 'Proxy', '*', 'PROD1', null),
    (27, 4, 'CCS', '*', 'PROD1', null),
    (28, 4, 'CCS-FDR', '*', 'PROD1', null),
    (29, 4, 'MS', '*', 'PROD1', null),
    (30, 4, 'CEW', '*', 'PROD1', null),
    (31, 4, 'GEB', '*', 'PROD1', null),
    (32, 4, 'FDR', '*', 'PROD1', null),
    (33, 5, 'CCS', 'SG', 'SIT3', 'no endpoints recorded for this slice yet'),
    (34, 5, 'Global-CCS', '*', 'SIT2', 'no endpoints recorded for this slice yet'),
    (35, 5, 'CCS', 'CN', 'SIT5', 'no endpoints recorded for this slice yet');

insert into env_release_link (release_id, source_node_id, target_node_id, direction, note)
values
    (1, 1, 3, 'BIDIRECTIONAL', 'regional CCS reports up to the global instance'),
    (1, 3, 4, 'BIDIRECTIONAL', 'CCS exchanges with the FDR front door'),
    (1, 4, 8, 'BIDIRECTIONAL', 'front door fronts the FDR backend'),
    (1, 2, 5, 'ONE_WAY', 'north-south ingress'),
    (1, 2, 6, 'ONE_WAY', 'north-south ingress'),
    (1, 2, 7, 'ONE_WAY', 'north-south ingress'),
    (1, 3, 5, 'ONE_WAY', 'east-west service call'),
    (1, 3, 6, 'ONE_WAY', 'east-west service call'),
    (1, 3, 7, 'ONE_WAY', 'east-west service call'),
    (2, 9, 11, 'BIDIRECTIONAL', 'regional CCS reports up to the global instance'),
    (2, 11, 12, 'BIDIRECTIONAL', 'CCS exchanges with the FDR front door'),
    (2, 12, 16, 'BIDIRECTIONAL', 'front door fronts the FDR backend'),
    (2, 10, 13, 'ONE_WAY', 'north-south ingress'),
    (2, 10, 14, 'ONE_WAY', 'north-south ingress'),
    (2, 10, 15, 'ONE_WAY', 'north-south ingress'),
    (2, 11, 13, 'ONE_WAY', 'east-west service call'),
    (2, 11, 14, 'ONE_WAY', 'east-west service call'),
    (2, 11, 15, 'ONE_WAY', 'east-west service call'),
    (3, 17, 19, 'BIDIRECTIONAL', 'regional CCS reports up to the global instance'),
    (3, 19, 20, 'BIDIRECTIONAL', 'CCS exchanges with the FDR front door'),
    (3, 20, 24, 'BIDIRECTIONAL', 'front door fronts the FDR backend'),
    (3, 18, 21, 'ONE_WAY', 'north-south ingress'),
    (3, 18, 22, 'ONE_WAY', 'north-south ingress'),
    (3, 18, 23, 'ONE_WAY', 'north-south ingress'),
    (3, 19, 21, 'ONE_WAY', 'east-west service call'),
    (3, 19, 22, 'ONE_WAY', 'east-west service call'),
    (3, 19, 23, 'ONE_WAY', 'east-west service call'),
    (4, 25, 27, 'BIDIRECTIONAL', 'regional CCS reports up to the global instance'),
    (4, 27, 28, 'BIDIRECTIONAL', 'CCS exchanges with the FDR front door'),
    (4, 28, 32, 'BIDIRECTIONAL', 'front door fronts the FDR backend'),
    (4, 26, 29, 'ONE_WAY', 'north-south ingress'),
    (4, 26, 30, 'ONE_WAY', 'north-south ingress'),
    (4, 26, 31, 'ONE_WAY', 'north-south ingress'),
    (4, 27, 29, 'ONE_WAY', 'east-west service call'),
    (4, 27, 30, 'ONE_WAY', 'east-west service call'),
    (4, 27, 31, 'ONE_WAY', 'east-west service call'),
    (5, 33, 34, 'ONE_WAY', 'regional CCS in SG reports to the global instance'),
    (5, 34, 35, 'ONE_WAY', 'global instance fans out to the CN region');

-- Explicit ids above; move the sequences past them or the first insert from the UI collides.
select setval('env_release_id_seq', (select max(id) from env_release));
select setval('env_release_node_id_seq', (select max(id) from env_release_node));
