/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.ranger.resource.mapper.hive.event.fetch;

import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.hadoop.hive.metastore.TableType;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.hadoop.hive.metastore.api.PrincipalType;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.thirdparty.com.google.common.collect.Sets;
import org.apache.ranger.resource.mapper.event.ResourceDiffSource;

@Slf4j
public abstract class BaseHiveMetastoreFetcher implements ResourceDiffSource {
    public static final Set<String> SUPPORTED_TABLE_TYPES = Sets.newHashSet(
        TableType.EXTERNAL_TABLE.name(),
        TableType.MANAGED_TABLE.name()
    );

    protected boolean isSupportedTable(String tableName, String tableType) {
        boolean isSupportedTableType = SUPPORTED_TABLE_TYPES.contains(tableType);
        if (!isSupportedTableType) {
            log.info("Skipping unsupported table {} of type: {}", tableName, tableType);
        }
        return isSupportedTableType;
    }

    protected String fullName(String... nameParts) {
        return String.join(".", nameParts);
    }

    /**
     * @return the user owning the table, or null if a role or a group owns it: {OWNER} in Hive policies
     *         is matched against the user name only
     */
    public static String userOwner(Table table) {
        return userOwner(table.getOwner(), table.getOwnerType());
    }

    /**
     * @return the user owning the database, or null if a role or a group owns it
     */
    public static String userOwner(Database database) {
        return userOwner(database.getOwnerName(), database.getOwnerType());
    }

    private static String userOwner(String owner, PrincipalType ownerType) {
        if (owner == null || owner.trim().isEmpty()) {
            return null;
        }
        // HMS treats a missing owner type as a user one
        return ownerType == null || ownerType == PrincipalType.USER ? owner : null;
    }
}
