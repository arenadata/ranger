CREATE DATABASE testdb;

\connect testdb;

DROP TABLE IF EXISTS x_metastore_mapping_diff;

CREATE TABLE x_metastore_mapping_diff
(
    id       BIGINT        NOT NULL,
    old_name       varchar(1024) NOT NULL,
    old_location   varchar(2048) NOT NULL,
    new_name       varchar(1024),
    new_location   varchar(2048),
    entity_type    varchar(255)  NOT NULL,
    diff_type      varchar(255)  NOT NULL,
    source_service varchar(255)  NOT NULL,
    target_service varchar(255)  NOT NULL,
    PRIMARY KEY (id)
);