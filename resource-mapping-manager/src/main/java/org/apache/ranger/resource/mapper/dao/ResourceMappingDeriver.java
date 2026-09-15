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

import java.util.List;
import org.apache.ranger.resource.mapper.model.ResourceMapping;
import org.apache.ranger.resource.mapper.model.ResourceMappingDiff;

/**
 * Derives additional resource mapping diffs for other target services from a diff produced by
 * the source service fetcher (e.g. StarRocks mappings from a Hive table diff).
 */
public interface ResourceMappingDeriver {
    /**
     * @return diffs for the derived target service(s), possibly empty; never the given diff itself
     */
    List<ResourceMappingDiff> derive(ResourceMappingDiff diff);

    /**
     * @return derived mappings identifying the same entity in the derived target service(s)
     */
    List<ResourceMapping> derive(ResourceMapping mapping);
}
