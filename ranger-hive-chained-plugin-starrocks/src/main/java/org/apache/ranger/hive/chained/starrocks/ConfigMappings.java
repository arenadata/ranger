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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.ranger.authorization.hive.authorizer.HiveAccessType;

/**
 * Default StarRocks -> Hive access type mappings and the configuration keys to override them.
 *
 * <p>StarRocks access types are the lower-cased names of StarRocks privilege types as they are
 * sent by the StarRocks FE Ranger plugin (multi-word ones contain spaces, e.g. {@code create table}).
 * Every mapping can be overridden with the property
 * {@code ranger.plugin.starrocks.hive.<database|table|column>.access.mappings.<access type>}
 * where spaces in the access type are replaced with underscores, e.g.
 * {@code ranger.plugin.starrocks.hive.database.access.mappings.create_table=create}.
 */
public final class ConfigMappings {
    public static final String ACCESS_MAPPINGS_KEY_TEMPLATE = ".hive.%s.access.mappings.%s";

    public static final String SELECT = "select";
    public static final String INSERT = "insert";
    public static final String UPDATE = "update";
    public static final String DELETE = "delete";
    public static final String DROP = "drop";
    public static final String ALTER = "alter";
    public static final String EXPORT = "export";
    public static final String REFRESH = "refresh";
    public static final String USAGE = "usage";
    public static final String CREATE_TABLE = "create table";
    public static final String CREATE_VIEW = "create view";
    public static final String CREATE_MATERIALIZED_VIEW = "create materialized view";
    public static final String CREATE_FUNCTION = "create function";

    public static final Map<String, HiveAccessType[]> DATABASE_ACCESS_MAPPINGS_DEFAULT =
        createDefaultDatabaseMappings();
    public static final Map<String, HiveAccessType[]> TABLE_ACCESS_MAPPINGS_DEFAULT =
        createDefaultTableMappings();
    public static final Map<String, HiveAccessType[]> COLUMN_ACCESS_MAPPINGS_DEFAULT =
        createDefaultColumnMappings();

    private ConfigMappings() {
    }

    private static Map<String, HiveAccessType[]> createDefaultDatabaseMappings() {
        Map<String, HiveAccessType[]> map = new LinkedHashMap<>();
        map.put(USAGE, types(HiveAccessType.USE));
        map.put(CREATE_TABLE, types(HiveAccessType.CREATE));
        map.put(CREATE_VIEW, types(HiveAccessType.CREATE));
        map.put(CREATE_MATERIALIZED_VIEW, types(HiveAccessType.CREATE));
        map.put(CREATE_FUNCTION, types(HiveAccessType.CREATE));
        map.put(DROP, types(HiveAccessType.DROP));
        map.put(ALTER, types(HiveAccessType.ALTER));
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, HiveAccessType[]> createDefaultTableMappings() {
        Map<String, HiveAccessType[]> map = new LinkedHashMap<>();
        map.put(SELECT, types(HiveAccessType.SELECT));
        map.put(EXPORT, types(HiveAccessType.SELECT));
        map.put(REFRESH, types(HiveAccessType.SELECT));
        map.put(INSERT, types(HiveAccessType.UPDATE));
        map.put(UPDATE, types(HiveAccessType.UPDATE));
        map.put(DELETE, types(HiveAccessType.UPDATE));
        map.put(DROP, types(HiveAccessType.DROP));
        map.put(ALTER, types(HiveAccessType.ALTER));
        map.put(USAGE, types(HiveAccessType.USE));
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, HiveAccessType[]> createDefaultColumnMappings() {
        Map<String, HiveAccessType[]> map = new LinkedHashMap<>();
        map.put(SELECT, types(HiveAccessType.SELECT));
        return Collections.unmodifiableMap(map);
    }

    private static HiveAccessType[] types(HiveAccessType... accessTypes) {
        return accessTypes;
    }
}
