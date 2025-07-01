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

package org.apache.ranger.hive.chained.hdfs;

import java.net.URI;
import java.net.URISyntaxException;
import org.apache.ranger.admin.client.RangerAdminClient;
import org.apache.ranger.hive.chained.mapping.HiveMappingFetcher;
import org.apache.ranger.hive.chained.mapping.HiveResourceMappingStore;
import org.apache.ranger.plugin.model.ResourceMapping;

public class HdfsHiveMappingFetcher extends HiveMappingFetcher {
    public HdfsHiveMappingFetcher(RangerAdminClient adminClient,
                                  HiveResourceMappingStore mappingStore,
                                  long refreshInterval,
                                  long mappingsFlushInterval,
                                  String targetService) {
        super(adminClient, mappingStore, refreshInterval, mappingsFlushInterval, targetService);
    }

    @Override
    protected ResourceMapping transformMapping(ResourceMapping mapping) {
        return new ResourceMapping(
            mapping.getName(),
            extractRelativePath(mapping.getLocation())
        );
    }

    private String extractRelativePath(String fullPath) {
        try {
            return new URI(fullPath).getPath();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Error extracting relative path from full HDFS path " + fullPath, e);
        }
    }
}
