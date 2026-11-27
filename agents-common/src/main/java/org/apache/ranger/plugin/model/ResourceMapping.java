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
public class ResourceMapping implements Serializable {
    private final String name;
    private final String location;
    private final String owner;

    public ResourceMapping(String name, String location) {
        this(name, location, null);
    }

    @JsonCreator
    public ResourceMapping(
        @JsonProperty(value = "name")
        String name,
        @JsonProperty(value = "location")
        String location,
        @JsonProperty(value = "owner")
        String owner) {
        this.name = name;
        this.location = location;
        this.owner = owner;
    }

    public String getName() {
        return name;
    }

    public String getLocation() {
        return location;
    }

    /**
     * @return the user owning the Hive entity, or null if a role or a group owns it or the owner is unknown
     */
    public String getOwner() {
        return owner;
    }

    /**
     * @return the same entity at another location, e.g. the location converted to the form of the target service
     */
    public ResourceMapping withLocation(String location) {
        return new ResourceMapping(name, location, owner);
    }
}
