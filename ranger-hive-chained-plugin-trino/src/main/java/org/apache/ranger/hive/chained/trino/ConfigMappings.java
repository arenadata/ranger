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

package org.apache.ranger.hive.chained.trino;

import org.apache.ranger.authorization.hive.authorizer.HiveAccessType;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ConfigMappings
{
    public static final String ACCESS_MAPPINGS_KEY_TEMPLATE = ".hive.%s.access.mappings.%s";
    public static final Map<RangerTrinoAccessType, List<HiveAccessType>> DATABASE_ACCESS_MAPPING_DEFAULT =
            createDefaultSchemaMappings();
    public static final Map<RangerTrinoAccessType, List<HiveAccessType>> TABLE_ACCESS_MAPPING_DEFAULT =
            createDefaultTableMappings();
    public static final Map<RangerTrinoAccessType, List<HiveAccessType>> COLUMN_ACCESS_MAPPING_DEFAULT =
            createDefaultColumnMappings();

    private static Map<RangerTrinoAccessType, List<HiveAccessType>> createDefaultSchemaMappings()
    {
        Map<RangerTrinoAccessType, List<HiveAccessType>> map = new HashMap<>();
        map.put(RangerTrinoAccessType.USE, Arrays.asList(HiveAccessType.USE));
        map.put(RangerTrinoAccessType.SELECT, Arrays.asList(HiveAccessType.USE));
        map.put(RangerTrinoAccessType.CREATE, Arrays.asList(HiveAccessType.CREATE));
        map.put(RangerTrinoAccessType.DROP, Arrays.asList(HiveAccessType.DROP));
        map.put(RangerTrinoAccessType.ALTER, Arrays.asList(HiveAccessType.ALTER));
        map.put(RangerTrinoAccessType.SHOW, Arrays.asList(HiveAccessType.USE));
        map.put(RangerTrinoAccessType._ANY, Arrays.asList(HiveAccessType.USE));
        map.put(RangerTrinoAccessType.ALL, Arrays.asList(HiveAccessType.ALL));
        return map;
    }

    private static Map<RangerTrinoAccessType, List<HiveAccessType>> createDefaultTableMappings()
    {
        Map<RangerTrinoAccessType, List<HiveAccessType>> map = new HashMap<>();
        map.put(RangerTrinoAccessType.SELECT, Arrays.asList(HiveAccessType.SELECT));
        map.put(RangerTrinoAccessType.INSERT, Arrays.asList(HiveAccessType.UPDATE));
        map.put(RangerTrinoAccessType.DELETE, Arrays.asList(HiveAccessType.UPDATE));
        map.put(RangerTrinoAccessType.CREATE, Arrays.asList(HiveAccessType.CREATE));
        map.put(RangerTrinoAccessType.DROP, Arrays.asList(HiveAccessType.DROP));
        map.put(RangerTrinoAccessType.ALTER, Arrays.asList(HiveAccessType.ALTER));
        map.put(RangerTrinoAccessType.USE, Arrays.asList(HiveAccessType.USE));
        map.put(RangerTrinoAccessType.SHOW, Arrays.asList(HiveAccessType.USE));
        map.put(RangerTrinoAccessType._ANY, Arrays.asList(HiveAccessType.USE));
        map.put(RangerTrinoAccessType.ALL, Arrays.asList(HiveAccessType.ALL));
        return map;
    }

    private static Map<RangerTrinoAccessType, List<HiveAccessType>> createDefaultColumnMappings()
    {
        Map<RangerTrinoAccessType, List<HiveAccessType>> map = new HashMap<>();
        map.put(RangerTrinoAccessType.SELECT, Arrays.asList(HiveAccessType.SELECT));
        map.put(RangerTrinoAccessType._ANY, Arrays.asList(HiveAccessType.USE));
        map.put(RangerTrinoAccessType.ALL, Arrays.asList(HiveAccessType.ALL));
        return map;
    }
}
