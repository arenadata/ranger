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

package org.apache.ranger.hive.chained.mapping.persist;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import lombok.Getter;
import org.apache.ranger.authorization.hive.authorizer.HiveObjectType;
import org.apache.ranger.hive.chained.mapping.HiveEntity;

public class JsonMappingSerializer implements MappingSerializer {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String serialize(String path, HiveEntity entity) {
        try {
            return mapper.writeValueAsString(new Mapping(path, entity.fullName(), entity.getType()));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Error serializing mapping", e);
        }
    }

    @Override
    public void deserialize(String value, Map<String, HiveEntity> accumulator) {
        try {
            Mapping mapping = mapper.readValue(value, Mapping.class);
            accumulator.put(mapping.path, new HiveEntity(mapping.name, mapping.type));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Error deserializing mapping", e);
        }
    }

    @Getter
    private static class Mapping {
        private final String path;
        private final String name;
        private final HiveObjectType type;

        @JsonCreator
        public Mapping(
            @JsonProperty(value = "path")
            String path,
            @JsonProperty(value = "name")
            String name,
            @JsonProperty(value = "type")
            HiveObjectType type) {
            this.path = path;
            this.name = name;
            this.type = type;
        }
    }

}
