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

package org.apache.ranger.hive.chained.starrocks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.ranger.authorization.hive.authorizer.HiveObjectType;
import org.apache.ranger.authorization.hive.authorizer.RangerHiveResource;
import org.apache.ranger.hive.chained.mapping.HiveEntity;
import org.apache.ranger.plugin.policyengine.RangerAccessResource;

/**
 * Builds the Hive resource authorized on behalf of a StarRocks resource.
 *
 * <p>The mapping found in the store covers a prefix of the StarRocks resource: the whole database
 * ({@code catalog.database}) or a table ({@code catalog.database.table}). The levels of the
 * StarRocks resource below the mapped prefix (table and/or column) are carried over to the Hive
 * resource as is, so a database mapping authorizes every table of the database by name and a
 * table mapping authorizes its columns.
 */
public final class StarRocksHiveResources {
    /** Index of the database segment in the Hive entity name ({@code hms_catalog.database.table}). */
    private static final int DATABASE_SEGMENT = 1;
    private static final int TABLE_SEGMENT = 2;

    private StarRocksHiveResources() {
    }

    public static RangerHiveResource toHiveResource(HiveEntity entity, RangerAccessResource resource) {
        List<String> hiveNameSegments = entity.getNameSegments();
        List<String> hiveLevels = new ArrayList<>(3);
        int mappedDepth;

        if (entity.getType() == HiveObjectType.DATABASE) {
            if (hiveNameSegments.size() <= DATABASE_SEGMENT) {
                throw new IllegalArgumentException("Wrong db name: " + entity.fullName());
            }
            hiveLevels.add(hiveNameSegments.get(DATABASE_SEGMENT));
            mappedDepth = StarRocksResourcePath.HIERARCHY.indexOf(StarRocksResourcePath.KEY_DATABASE) + 1;
        } else {
            if (hiveNameSegments.size() <= TABLE_SEGMENT) {
                throw new IllegalArgumentException("Wrong entity name: " + entity.fullName());
            }
            hiveLevels.add(hiveNameSegments.get(DATABASE_SEGMENT));
            hiveLevels.add(hiveNameSegments.get(TABLE_SEGMENT));
            mappedDepth = StarRocksResourcePath.HIERARCHY.indexOf(StarRocksResourcePath.KEY_TABLE) + 1;
        }

        List<String> requestSegments = StarRocksResourcePath.segments(resource)
            .orElse(Collections.emptyList());
        if (requestSegments.size() > mappedDepth) {
            hiveLevels.addAll(requestSegments.subList(mappedDepth, requestSegments.size()));
        }

        switch (hiveLevels.size()) {
            case 1:
                return new RangerHiveResource(HiveObjectType.DATABASE, hiveLevels.get(0));
            case 2:
                return new RangerHiveResource(HiveObjectType.TABLE, hiveLevels.get(0), hiveLevels.get(1));
            case 3:
                return new RangerHiveResource(
                    HiveObjectType.COLUMN, hiveLevels.get(0), hiveLevels.get(1), hiveLevels.get(2));
            default:
                throw new IllegalArgumentException(
                    "Can't map StarRocks resource " + resource.getAsString() + " with " + entity);
        }
    }
}
