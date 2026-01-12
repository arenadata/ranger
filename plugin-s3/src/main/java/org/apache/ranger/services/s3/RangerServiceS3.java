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

package org.apache.ranger.services.s3;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.ranger.plugin.service.RangerBaseService;
import org.apache.ranger.plugin.service.ResourceLookupContext;
import org.apache.ranger.services.s3.client.S3ResourceManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RangerServiceS3 provides integration between Apache Ranger and Amazon S3 compatible storage services.
 * This service implementation enables policy-based access control for S3 buckets and objects.
 * 
 * <p>The service supports:
 * <ul>
 *   <li>Connection validation to S3 endpoints</li>
 *   <li>Resource lookup for buckets and paths</li>
 *   <li>Integration with Ranger policy engine</li>
 * </ul>
 */
public class RangerServiceS3 extends RangerBaseService {

    private static final Log LOG = LogFactory.getLog(RangerServiceS3.class);

    /**
     * Validates the configuration for connecting to an S3 service.
     * 
     * @return Map containing validation results with status and error messages if any
     * @throws Exception if validation fails due to connection or configuration errors
     */
    @Override
    public Map<String, Object> validateConfig() throws Exception {
        Map<String, Object> ret = new HashMap<String, Object>();
        String serviceName = getServiceName();

        if (LOG.isDebugEnabled()) {
            LOG.debug("RangerServiceS3.validateConfig(): Service: " + serviceName);
        }

        if (configs != null) {
            try {
                ret = S3ResourceManager.validateConfig(configs);
            } catch (Exception e) {
                LOG.error("Error validating S3 config for service: " + serviceName, e);
                throw e;
            }
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("RangerServiceS3.validateConfig(): Response: " + ret);
        }
        return ret;
    }

    /**
     * Looks up S3 resources (buckets and paths) based on the provided context.
     * This method is used by the Ranger Admin UI to provide auto-complete functionality
     * when creating policies.
     * 
     * @param context ResourceLookupContext containing user input and resource hierarchy information
     * @return List of matching resource names (bucket names or paths)
     * @throws Exception if resource lookup fails due to connection or permission errors
     */
    @Override
    public List<String> lookupResource(ResourceLookupContext context) throws Exception {
        List<String> ret = new ArrayList<String>();

        if (LOG.isDebugEnabled()) {
            LOG.debug("RangerServiceS3.lookupResource() Context: " + context);
        }

        if (context != null) {
            try {
                ret = S3ResourceManager.getResources(configs, context);
            } catch (Exception e) {
                LOG.error("Error looking up S3 resources", e);
                throw e;
            }
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("RangerServiceS3.lookupResource() Response: " + ret);
        }
        return ret;
    }
}
