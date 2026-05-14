-- Licensed to the Apache Software Foundation (ASF) under one or more
-- contributor license agreements.  See the NOTICE file distributed with
-- this work for additional information regarding copyright ownership.
-- The ASF licenses this file to You under the Apache License, Version 2.0
-- (the "License"); you may not use this file except in compliance with
-- the License.  You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.

call spdropsequence('X_RANGER_DT_MASTER_KEY_SEQ');
CREATE SEQUENCE X_RANGER_DT_MASTER_KEY_SEQ START WITH 1 INCREMENT BY 1 NOCACHE NOCYCLE;

call spdropsequence('X_RANGER_DELEGATION_TOKEN_SEQ');
CREATE SEQUENCE X_RANGER_DELEGATION_TOKEN_SEQ START WITH 1 INCREMENT BY 1 NOCACHE NOCYCLE;

CREATE TABLE x_ranger_dt_master_key (
    id          NUMBER(20) NOT NULL,
    key_id      NUMBER(10) NOT NULL,
    expiry_date NUMBER(20) NOT NULL,
    key_bytes   BLOB NOT NULL,
    create_time DATE DEFAULT SYSDATE,
    PRIMARY KEY (id),
    CONSTRAINT uk_dt_mk_key_id UNIQUE (key_id)
);

CREATE TABLE x_ranger_delegation_token (
    id              NUMBER(20) NOT NULL,
    sequence_number NUMBER(10) NOT NULL,
    owner           VARCHAR2(255) NOT NULL,
    renewer         VARCHAR2(255),
    real_user       VARCHAR2(255),
    issue_date      NUMBER(20) NOT NULL,
    max_date        NUMBER(20) NOT NULL,
    renew_date      NUMBER(20) NOT NULL,
    master_key_id   NUMBER(10) NOT NULL,
    token_password  BLOB NOT NULL,
    create_time     DATE DEFAULT SYSDATE,
    PRIMARY KEY (id),
    CONSTRAINT uk_dt_seq_number UNIQUE (sequence_number)
);

commit;
