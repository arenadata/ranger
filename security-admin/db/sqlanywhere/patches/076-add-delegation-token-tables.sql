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

call dbo.removeForeignKeysAndTable('x_ranger_dt_master_key')
GO

CREATE TABLE dbo.x_ranger_dt_master_key (
    id          bigint IDENTITY NOT NULL,
    key_id      int NOT NULL,
    expiry_date bigint NOT NULL,
    key_bytes   varbinary(max) NOT NULL,
    create_time datetime DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT x_ranger_dt_master_key_PK_id PRIMARY KEY CLUSTERED(id),
    CONSTRAINT x_ranger_dt_master_key_UK_key_id UNIQUE(key_id)
) GO

call dbo.removeForeignKeysAndTable('x_ranger_delegation_token')
GO

CREATE TABLE dbo.x_ranger_delegation_token (
    id              bigint IDENTITY NOT NULL,
    sequence_number int NOT NULL,
    owner           varchar(255) NOT NULL,
    renewer         varchar(255),
    real_user       varchar(255),
    issue_date      bigint NOT NULL,
    max_date        bigint NOT NULL,
    renew_date      bigint NOT NULL,
    master_key_id   int NOT NULL,
    token_password  varbinary(max) NOT NULL,
    create_time     datetime DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT x_ranger_delegation_token_PK_id PRIMARY KEY CLUSTERED(id),
    CONSTRAINT x_ranger_delegation_token_UK_seq UNIQUE(sequence_number)
) GO
EXIT
