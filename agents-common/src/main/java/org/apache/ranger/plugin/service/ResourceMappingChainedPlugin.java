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

package org.apache.ranger.plugin.service;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import org.apache.ranger.authorization.hadoop.config.RangerChainedPluginConfig;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.policyengine.RangerAccessRequest;
import org.apache.ranger.plugin.policyengine.RangerAccessResult;

public abstract class ResourceMappingChainedPlugin extends RangerChainedPlugin {
    protected ResourceMappingChainedPlugin(RangerBasePlugin rootPlugin,
                                           String serviceType,
                                           String serviceName) {
        super(rootPlugin, serviceType, serviceName);
    }

    @Override
    public RangerAccessResult evalRowFilterPolicies(RangerAccessRequest request) {
        List<RangerAccessResult> results = toChainedRequests(request)
            .stream()
            .map(req -> plugin.evalRowFilterPolicies(req, null))
            .collect(Collectors.toList());
        return reduceAccessResults(request, this::handleRowFilterResult, results);
    }

    @Override
    public RangerAccessResult evalDataMaskPolicies(RangerAccessRequest request) {
        List<RangerAccessResult> results = toChainedRequests(request)
            .stream()
            .map(req -> plugin.evalDataMaskPolicies(req, null))
            .collect(Collectors.toList());
        return reduceAccessResults(request, this::handleDataMaskResult, results);
    }

    @Override
    public Collection<RangerAccessResult> isAccessAllowed(Collection<RangerAccessRequest> requests) {
        List<RangerAccessRequest> mappedRequests = requests.stream()
            .map(this::toChainedRequests)
            .flatMap(Collection::stream)
            .collect(Collectors.toList());

        return plugin.isAccessAllowed(mappedRequests);
    }

    @Override
    public RangerAccessResult isAccessAllowed(RangerAccessRequest request) {
        List<RangerAccessRequest> chainedRequests = toChainedRequests(request);
        if (chainedRequests.size() == 1) {
            return plugin.isAccessAllowed(chainedRequests.get(0));
        }

        return reduceAccessResults(
            request, plugin.isAccessAllowed(chainedRequests)
        );
    }

    @Override
    protected RangerBasePlugin buildChainedPlugin(String serviceType, String serviceName, String appId) {
        return new RangerBasePlugin(
            new RangerChainedPluginConfig(serviceType, serviceName, appId, rootPlugin.getConfig())
        );
    }

    protected abstract List<RangerAccessRequest> toChainedRequests(RangerAccessRequest request);

    protected RangerAccessResult getAllowedResult(RangerAccessRequest request) {
        RangerAccessResult rangerAccessResult =
            new RangerAccessResult(RangerPolicy.POLICY_TYPE_ACCESS, serviceName, null, request);
        rangerAccessResult.setIsAccessDetermined(true);
        rangerAccessResult.setIsAllowed(true);
        return rangerAccessResult;
    }

    protected RangerAccessResult reduceAccessResults(RangerAccessRequest request,
                                                     Collection<RangerAccessResult> results) {

        return reduceAccessResults(request, (acc, req) -> {}, results);
    }

    protected void handleRowFilterResult(RangerAccessResult finalResult, RangerAccessResult partialResult) {
        if (partialResult.getPolicyType() == RangerPolicy.POLICY_TYPE_ROWFILTER) {
            Optional.ofNullable(partialResult.getFilterExpr())
                .ifPresent(finalResult::setFilterExpr);
        }
    }

    protected void handleDataMaskResult(RangerAccessResult finalResult, RangerAccessResult partialResult) {
        if (partialResult.getPolicyType() != RangerPolicy.POLICY_TYPE_DATAMASK) {
            return;
        }

        Optional.ofNullable(partialResult.getMaskType())
            .ifPresent(finalResult::setMaskType);

        Optional.ofNullable(partialResult.getMaskCondition())
            .ifPresent(finalResult::setMaskCondition);

        Optional.ofNullable(partialResult.getMaskedValue())
            .ifPresent(finalResult::setMaskedValue);
    }

    protected RangerAccessResult reduceAccessResults(RangerAccessRequest request,
                                                     BiConsumer<RangerAccessResult, RangerAccessResult> resultHandler,
                                                     Collection<RangerAccessResult> results) {

        RangerAccessResult allowedResult = getAllowedResult(request);
        for (RangerAccessResult accessResult : results) {
            if (accessResult.getIsAccessDetermined() && !accessResult.getIsAllowed()) {
                return accessResult;
            }

            resultHandler.accept(allowedResult, accessResult);
        }
        return allowedResult;
    }
}
