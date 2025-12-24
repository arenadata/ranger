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

package org.apache.ranger.services.s3.client;

import org.apache.ranger.plugin.service.ResourceLookupContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * S3ResourceManager provides static utility methods for managing S3 client instances
 * and performing resource operations such as configuration validation and resource lookup.
 * 
 * <p>This class acts as a facade for S3Client operations, ensuring proper error handling
 * and resource management.
 */
public class S3ResourceManager {

    /**
     * Creates and returns an S3Client instance configured with the provided settings.
     * 
     * @param configs Map containing S3 connection configuration (endpoint, accesskey, password, region)
     * @return Configured S3Client instance, or null if configs is null
     * @throws Exception if client creation fails due to invalid configuration
     */
    public static S3Client getS3Client(Map<String, String> configs) throws Exception {
        if (configs != null) {
            return new S3Client(configs);
        } else {
            return null;
        }
    }

    /**
     * Validates the S3 service configuration by attempting to establish a connection
     * and perform a basic operation (list buckets).
     * 
     * @param configs Map containing S3 connection configuration
     * @return Map containing validation results with 'connectivityStatus' and 'message' fields
     * @throws Exception if validation fails due to connection or authentication errors
     */
    public static Map<String, Object> validateConfig(Map<String, String> configs) throws Exception {
        Map<String, Object> ret = new HashMap<>();
        S3Client client = getS3Client(configs);

        if (client != null) {
            ret = client.connectionTest();
        }
        return ret;
    }

    /**
     * Retrieves a list of S3 resources (buckets and paths) matching the user input from the context.
     * This method is used to provide auto-complete functionality in the Ranger Admin UI.
     * 
     * @param configs Map containing S3 connection configuration
     * @param context ResourceLookupContext containing user input and resource hierarchy
     * @return List of matching resource paths, or null if no matches found
     * @throws Exception if resource lookup fails due to connection or permission errors
     */
    public static List<String> getResources(Map<String, String> configs, ResourceLookupContext context) throws Exception {
        String userInput = context.getUserInput();
        List<String> resources = null;
        final S3Client client = getS3Client(configs);

        if (client != null) {
            resources = client.getResourcePaths(userInput);
        }
        return resources;
    }
}
