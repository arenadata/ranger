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

SET ANSI_NULLS ON
SET QUOTED_IDENTIFIER ON
SET ANSI_PADDING ON
GO

IF (OBJECT_ID('x_ranger_dt_master_key') IS NULL)
BEGIN
    CREATE TABLE [dbo].[x_ranger_dt_master_key] (
        [id]          [bigint] IDENTITY(1,1) NOT NULL,
        [key_id]      [int]    NOT NULL,
        [expiry_date] [bigint] NOT NULL,
        [key_bytes]   [varbinary](max) NOT NULL,
        [create_time] [datetime] DEFAULT GETDATE(),
        PRIMARY KEY CLUSTERED ([id] ASC),
        CONSTRAINT [x_ranger_dt_mk_UK_key_id] UNIQUE NONCLUSTERED ([key_id] ASC)
    )
END
GO

IF (OBJECT_ID('x_ranger_delegation_token') IS NULL)
BEGIN
    CREATE TABLE [dbo].[x_ranger_delegation_token] (
        [id]              [bigint]       IDENTITY(1,1) NOT NULL,
        [sequence_number] [int]          NOT NULL,
        [owner]           [varchar](255) NOT NULL,
        [renewer]         [varchar](255) NULL,
        [real_user]       [varchar](255) NULL,
        [issue_date]      [bigint]       NOT NULL,
        [max_date]        [bigint]       NOT NULL,
        [renew_date]      [bigint]       NOT NULL,
        [master_key_id]   [int]          NOT NULL,
        [token_password]  [varbinary](max) NOT NULL,
        [create_time]     [datetime]     DEFAULT GETDATE(),
        PRIMARY KEY CLUSTERED ([id] ASC),
        CONSTRAINT [x_ranger_dt_UK_seq_number] UNIQUE NONCLUSTERED ([sequence_number] ASC)
    )
END
GO

exit
