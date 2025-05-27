/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ranger.plugin.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResourceMappingDiff implements Serializable {
    private final ResourceMapping oldEntity;
    private final ResourceMapping newEntity;
    private final String entityType;
    private final String diffType;
    private final long id;
    private final String sourceService;
    private final String targetService;

    @JsonCreator
    public ResourceMappingDiff(
        @JsonProperty(value = "oldEntity")
        ResourceMapping oldEntity,
        @JsonProperty(value = "newEntity")
        ResourceMapping newEntity,
        @JsonProperty(value = "entityType")
        String entityType,
        @JsonProperty(value = "diffType")
        String diffType,
        @JsonProperty(value = "id")
        long id,
        @JsonProperty(value = "sourceService")
        String sourceService,
        @JsonProperty(value = "targetService")
        String targetService
    ) {
        this.oldEntity = oldEntity;
        this.newEntity = newEntity;
        this.entityType = entityType;
        this.diffType = diffType;
        this.id = id;
        this.sourceService = sourceService;
        this.targetService = targetService;
    }

    public ResourceMapping getOldEntity() {
        return oldEntity;
    }

    public ResourceMapping getNewEntity() {
        return newEntity;
    }

    public String getEntityType() {
        return entityType;
    }

    public String getDiffType() {
        return diffType;
    }

    public String getSourceService() {
        return sourceService;
    }

    public String getTargetService() {
        return targetService;
    }

    public long getId() {
        return id;
    }
}
