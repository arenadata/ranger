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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.apache.ranger.plugin.policyengine.RangerAccessResource;

/**
 * Converts StarRocks resources ({@code catalog.database[.table[.column]]}) to the path form used as
 * a key of the Hive resource mapping store ({@code /catalog/database[/table[/column]]}).
 *
 * <p>Both the RMM mapping locations and the access request resources are normalized the same way
 * (lower-cased, separators unified), so a mapping written by RMM as {@code hive_catalog.db1.t1}
 * matches a StarRocks request for {@code hive_catalog.db1.t1}.
 */
public final class StarRocksResourcePath {
    public static final String KEY_CATALOG = "catalog";
    public static final String KEY_DATABASE = "database";
    public static final String KEY_TABLE = "table";
    public static final String KEY_COLUMN = "column";

    public static final String PATH_SEPARATOR = "/";
    public static final String NAME_SEPARATOR = ".";

    /** StarRocks resource hierarchy handled by the chained plugin, from the top level down. */
    public static final List<String> HIERARCHY = Collections.unmodifiableList(
        Arrays.asList(KEY_CATALOG, KEY_DATABASE, KEY_TABLE, KEY_COLUMN));

    /** Depth of the shortest path that can be mapped to a Hive resource (catalog + database). */
    public static final int MIN_MAPPABLE_DEPTH = 2;

    private static final Set<String> SUPPORTED_KEYS = new HashSet<>(HIERARCHY);
    private static final Pattern LOCATION_SPLITTER = Pattern.compile("[./]");

    private StarRocksResourcePath() {
    }

    /**
     * Builds the mapping store path for a StarRocks access resource.
     *
     * @return the path, or empty if the resource is not a catalog/database/table/column resource
     *         (system, user, function, view, etc.) or misses the catalog or database level
     */
    public static Optional<String> fromResource(RangerAccessResource resource) {
        return segments(resource)
            .filter(segments -> segments.size() >= MIN_MAPPABLE_DEPTH)
            .map(StarRocksResourcePath::toPath);
    }

    /**
     * Extracts the resource levels (catalog, database, table, column) in the original case.
     *
     * @return the levels present in the resource, or empty if the resource contains keys not
     *         belonging to the table hierarchy or the hierarchy has a gap
     */
    public static Optional<List<String>> segments(RangerAccessResource resource) {
        if (resource == null || resource.getKeys() == null || resource.getKeys().isEmpty()) {
            return Optional.empty();
        }
        if (!SUPPORTED_KEYS.containsAll(resource.getKeys())) {
            return Optional.empty();
        }

        List<String> segments = new ArrayList<>(HIERARCHY.size());
        for (String key : HIERARCHY) {
            Object value = resource.getValue(key);
            if (value == null) {
                break;
            }
            String segment = value.toString();
            if (StringUtils.isBlank(segment)) {
                return Optional.empty();
            }
            segments.add(segment);
        }

        // a level is missing in the middle of the hierarchy (e.g. table without database)
        if (segments.size() != resource.getKeys().size()) {
            return Optional.empty();
        }
        return Optional.of(segments);
    }

    /**
     * Normalizes an RMM mapping location. Accepted forms are {@code catalog.database[.table]}
     * and {@code /catalog/database[/table]}.
     *
     * @throws IllegalArgumentException if the location is blank or contains empty segments
     */
    public static String fromLocation(String location) {
        if (StringUtils.isBlank(location)) {
            throw new IllegalArgumentException("Empty StarRocks resource location");
        }

        String trimmed = StringUtils.strip(location.trim(), PATH_SEPARATOR);
        List<String> segments = Arrays.asList(LOCATION_SPLITTER.split(trimmed, -1));
        if (segments.isEmpty() || segments.stream().anyMatch(StringUtils::isBlank)) {
            throw new IllegalArgumentException("Malformed StarRocks resource location: " + location);
        }
        return toPath(segments);
    }

    public static String toPath(List<String> segments) {
        return segments.stream()
            .map(segment -> segment.trim().toLowerCase(Locale.ENGLISH))
            .collect(Collectors.joining(PATH_SEPARATOR, PATH_SEPARATOR, ""));
    }
}
