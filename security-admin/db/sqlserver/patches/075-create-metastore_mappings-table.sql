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

IF (OBJECT_ID('x_metastore_mapping_diff') IS NOT NULL)
BEGIN
    DROP TABLE [dbo].[x_metastore_mapping_diff]
END
GO

SET ANSI_NULLS ON
SET QUOTED_IDENTIFIER ON
SET ANSI_PADDING ON
GO

CREATE TABLE [dbo].[x_metastore_mapping_diff]
(
    [id] [bigint]       NOT NULL,
    [old_name] [varchar](1024) NOT NULL,
    [old_location] [varchar](2048) NOT NULL,
    [new_name] [varchar](1024),
    [new_location] [varchar](2048),
    [entity_type] [varchar](255)  NOT NULL,
    [diff_type] [varchar](255)  NOT NULL,
    [source_service] [varchar](255)  NOT NULL,
    [target_service] [varchar](255)  NOT NULL,
    PRIMARY KEY CLUSTERED
(
[id] ASC
) WITH (PAD_INDEX = OFF,STATISTICS_NORECOMPUTE = OFF,IGNORE_DUP_KEY = OFF,ALLOW_ROW_LOCKS = ON,ALLOW_PAGE_LOCKS = ON) ON [PRIMARY]
    ) ON [PRIMARY] TEXTIMAGE_ON [PRIMARY]
);
GO

exit