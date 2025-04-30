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

package org.apache.ranger.resource.mapper.dao;

import java.util.Optional;
import javax.sql.DataSource;
import org.apache.ranger.resource.mapper.model.ResourceMapping;
import org.apache.ranger.resource.mapper.model.ResourceMappingDiff;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public class ResourceMappingDiffDaoImpl implements ResourceMappingDiffDao {
    private static final String ID_FIELD = "id";
    private static final String OLD_NAME_FIELD = "old_name";
    private static final String OLD_LOCATION_FIELD = "old_location";
    private static final String NEW_NAME_FIELD = "new_name";
    private static final String NEW_LOCATION_FIELD = "new_location";
    private static final String ENTITY_TYPE_FIELD = "entity_type";
    private static final String DIFF_TYPE_FIELD = "diff_type";
    private static final String SOURCE_SERVICE_FIELD = "source_service";
    private static final String TARGET_SERVICE_FIELD = "target_service";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ResourceMappingDiffDaoImpl(DataSource dataSource) {
        this.jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
    }

    @Override
    public void insert(ResourceMappingDiff entityDiff) {
        jdbcTemplate.update(
            "INSERT INTO x_metastore_mapping_diff VALUES (" +
                ":id, " +
                ":old_name, " +
                ":old_location, " +
                ":new_name, " +
                ":new_location, " +
                ":entity_type, " +
                ":diff_type, " +
                ":source_service, " +
                ":target_service)",
            toMapParamSource(entityDiff));
    }

    @Override
    public Optional<Long> getLatestDiffId() {
        Long result = jdbcTemplate.getJdbcOperations().queryForObject(
            "SELECT MAX(id) FROM x_metastore_mapping_diff",
            Long.class);
        return Optional.ofNullable(result);
    }

    private MapSqlParameterSource toMapParamSource(ResourceMappingDiff entityDiff) {
        MapSqlParameterSource source = new MapSqlParameterSource();

        source.addValue(OLD_NAME_FIELD, entityDiff.getOldEntity().getName());
        source.addValue(OLD_LOCATION_FIELD, entityDiff.getOldEntity().getLocation());

        Optional<ResourceMapping> newEntityOpt = Optional.ofNullable(entityDiff.getNewEntity());
        source.addValue(NEW_NAME_FIELD,
            newEntityOpt.map(ResourceMapping::getName).orElse(null));
        source.addValue(NEW_LOCATION_FIELD,
            newEntityOpt.map(ResourceMapping::getLocation).orElse(null));

        source.addValue(ENTITY_TYPE_FIELD, entityDiff.getEntityType());
        source.addValue(DIFF_TYPE_FIELD, entityDiff.getDiffType());
        source.addValue(ID_FIELD, entityDiff.getId());
        source.addValue(SOURCE_SERVICE_FIELD, entityDiff.getSourceService());
        source.addValue(TARGET_SERVICE_FIELD, entityDiff.getTargetService());
        return source;
    }
}
