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

CREATE TABLE IF NOT EXISTS x_ranger_dt_master_key (
  id          BIGSERIAL PRIMARY KEY,
  key_id      INT NOT NULL UNIQUE,
  expiry_date BIGINT NOT NULL,
  key_bytes   BYTEA NOT NULL,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS x_ranger_delegation_token (
  id              BIGSERIAL PRIMARY KEY,
  sequence_number INT NOT NULL UNIQUE,
  owner           VARCHAR(255) NOT NULL,
  renewer         VARCHAR(255),
  real_user       VARCHAR(255),
  issue_date      BIGINT NOT NULL,
  max_date        BIGINT NOT NULL,
  renew_date      BIGINT NOT NULL,
  master_key_id   INT NOT NULL,
  token_password  BYTEA NOT NULL,
  create_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
