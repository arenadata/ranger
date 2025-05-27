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

package org.apache.ranger.rest;

import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import org.apache.commons.lang.StringUtils;
import org.apache.ranger.biz.ResourceMappingMgr;
import org.apache.ranger.common.RESTErrorUtil;
import org.apache.ranger.plugin.model.ResourceMappingDiffs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

@Component
@Path("/resource-mappings")
@Scope("request")
public class ResourceMappingsREST {
    private static final Logger LOG = LoggerFactory.getLogger(ResourceMappingsREST.class);
    private final ResourceMappingMgr resourceMappingMgr;
    private final RESTErrorUtil restErrorUtil;

    @Autowired
    public ResourceMappingsREST(ResourceMappingMgr resourceMappingMgr,
                                RESTErrorUtil restErrorUtil) {
        this.resourceMappingMgr = resourceMappingMgr;
        this.restErrorUtil = restErrorUtil;
    }

    @GET
    @Path("/{sourceService}/{targetService}/diffs/new")
    @Produces({"application/json"})
    public ResourceMappingDiffs getDiffs(@PathParam("sourceService") String sourceService,
                                         @PathParam("targetService") String targetService,
                                         @QueryParam("fromDiffId") Long diffId) {
        if (StringUtils.isBlank(sourceService)) {
            throw restErrorUtil.createRESTException("sourceService parameter is required");
        }

        if (StringUtils.isBlank(targetService)) {
            throw restErrorUtil.createRESTException("targetService parameter is required");
        }

        try {
            return diffId == null
                ? resourceMappingMgr.getAllDiffs(sourceService, targetService)
                : resourceMappingMgr.getDiffsNewerThan(sourceService, targetService, diffId);
        } catch (Exception exception) {
            String errorMessage = String.format(
                "Error getting resource mapping diffs for services %s-%s", sourceService, targetService);
            LOG.error(errorMessage, exception);
            throw restErrorUtil.createRESTException(
                HttpServletResponse.SC_INTERNAL_SERVER_ERROR, errorMessage, false);
        }
    }
}
