-- MySQL 8 copy of db/migration (PostgreSQL / H2), selected by the `mysql` profile. Same versions and same tables,
-- so the entities do not care which database they are on. Differences from the PostgreSQL scripts:
--   * auto_increment instead of identity, datetime(6) (UTC, hibernate.jdbc.time_zone) instead of timestamptz;
--   * longtext for the JSON columns: MySQL's text stops at 64 KB and a big analysis is megabytes;
--   * foreign keys as table constraints: MySQL silently ignores an inline `references`;
--   * the unique key on item_key indexes a 750-character prefix: InnoDB keys stop at 3072 bytes (utf8mb4 = 4 each).

create table mr_release (
    id          bigint auto_increment primary key,
    name        varchar(120)  not null,
    description varchar(1000),
    created_at  datetime(6)   not null,
    constraint ux_mr_release_name unique (name)
) engine = InnoDB default charset = utf8mb4 collate = utf8mb4_bin;

-- One run of the comparison. result_json holds everything but the items.
create table mr_analysis (
    id            bigint auto_increment primary key,
    release_id    bigint        not null,
    mode          varchar(16)   not null,
    repo1_path    varchar(1000) not null,
    repo2_path    varchar(1000) not null,
    baseline_ref  varchar(1000) not null,
    total_items   integer       not null,
    high_items    integer       not null,
    medium_items  integer       not null,
    low_items     integer       not null,
    duration_ms   bigint        not null,
    analyzed_at   datetime(6)   not null,
    result_json   longtext      not null,
    constraint fk_mr_analysis_release foreign key (release_id) references mr_release (id)
) engine = InnoDB default charset = utf8mb4 collate = utf8mb4_bin;
create index ix_mr_analysis_release on mr_analysis (release_id, analyzed_at);

-- One risk item and its review (see db/migration/V1 for the columns' meaning).
create table mr_item (
    id                bigint auto_increment primary key,
    analysis_id       bigint        not null,
    item_key          varchar(1000) not null,
    fingerprint       varchar(64)   not null,
    level             varchar(10)   not null,
    category          varchar(32)   not null,
    element           varchar(1000) not null,
    item_json         longtext      not null,
    review_status     varchar(16)   not null,
    review_note       varchar(2000),
    reviewed_by       varchar(120),
    reviewed_at       datetime(6),
    carried_over_from bigint,
    version           bigint        not null default 0,
    constraint fk_mr_item_analysis foreign key (analysis_id) references mr_analysis (id) on delete cascade,
    constraint ux_mr_item_key unique (analysis_id, item_key(750))
) engine = InnoDB default charset = utf8mb4 collate = utf8mb4_bin;
create index ix_mr_item_analysis on mr_item (analysis_id);
