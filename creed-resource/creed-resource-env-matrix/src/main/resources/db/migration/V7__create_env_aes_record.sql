-- Env Matrix Viewer — AES-encrypted property values, one row per server.
--
-- The AES page encrypts a value with a Secret Key / IV / randomkey the user types in, then saves the
-- ciphertext against every server ticked in its list. Only the ciphertext is stored: the keys are
-- never sent to this table (nor to any other), so a copy of the database cannot be decrypted with
-- what is in it.
--
-- A server is (app_system, host, ip) as listed in env_endpoint, but there is deliberately no
-- foreign key: endpoint rows are edited and re-saved as a whole table by the config page, and a
-- record must survive that — the same reasoning as env_release_node.

create table env_aes_record
(
    id              bigserial primary key,

    app_system      varchar(64)    not null,
    host            varchar(255)   not null,
    ip              varchar(45)    not null,
    -- The configuration property the value belongs to, e.g. spring.datasource.password.
    property_key    varchar(255)   not null,
    -- Base64 of AES/CBC/PKCS5Padding. 16384 covers the API's 4000-character plaintext limit
    -- (worst case 3 UTF-8 bytes per character, plus one padding block, times 4/3 for Base64).
    encrypted_value varchar(16384) not null,
    note            varchar(512),

    created_at      timestamptz    not null default now(),
    updated_at      timestamptz    not null default now(),
    version         bigint         not null default 0
);

-- Saving the same property for the same server again replaces the value instead of adding a row.
create unique index ux_env_aes_record_identity
    on env_aes_record (app_system, host, ip, property_key);

create index ix_env_aes_record_property on env_aes_record (property_key);
