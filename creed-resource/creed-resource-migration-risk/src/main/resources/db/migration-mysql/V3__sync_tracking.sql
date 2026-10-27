-- Sync tracking across runs of a release, review memory per file, Jira links, ignore rules.
alter table mr_analysis add column previous_analysis_id bigint;

alter table mr_item add column review_auto boolean not null default false;
alter table mr_item add column delta varchar(10);
alter table mr_item add column previous_status varchar(16);
alter table mr_item add column previous_note varchar(2000);
alter table mr_item add column previous_reviewer varchar(120);
alter table mr_item add column changed_parts text;
alter table mr_item add column jira_key varchar(40);

-- Findings nobody needs to review: matched files are left out of an analysis.
create table mr_ignore_rule (
    id         bigint auto_increment primary key,
    pattern    varchar(500)  not null,
    category   varchar(32),
    scope      varchar(1000),
    note       varchar(1000),
    created_at datetime(6)   not null
) engine = InnoDB default charset = utf8mb4 collate = utf8mb4_bin;
