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
import org.apache.ranger.authorization.hadoop.config.RangerPluginConfig;
import org.apache.ranger.plugin.audit.RangerDefaultAuditHandler;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.policyengine.RangerAccessRequest;
import org.apache.ranger.plugin.policyengine.RangerAccessResult;
import org.apache.ranger.plugin.policyengine.RangerAccessResultProcessor;

public abstract class ResourceMappingChainedPlugin extends RangerChainedPlugin {
    private static final String WITH_HIGH_PRIORITY_RESULT_CONF_SUFFIX =
        ".chained.plugin.result.priority.high";
    private static final boolean WITH_HIGH_PRIORITY_RESULT_CONF_DEFAULT = true;

    private final int resultPolicyPriority;

    protected ResourceMappingChainedPlugin(RangerBasePlugin rootPlugin,
                                           String serviceType,
                                           String serviceName) {
        super(rootPlugin, serviceType, serviceName);
        this.resultPolicyPriority = getPolicyPriority(rootPlugin.getConfig());
    }

    @Override
    public RangerAccessResult evalRowFilterPolicies(RangerAccessRequest request) {
        List<RangerAccessResult> results = toChainedRequests(request)
            .stream()
            .map(req -> plugin.evalRowFilterPolicies(req, auditHandler()))
            .collect(Collectors.toList());
        return reduceAccessResults(request, this::handleRowFilterResult, results);
    }

    @Override
    public RangerAccessResult evalDataMaskPolicies(RangerAccessRequest request) {
        List<RangerAccessResult> results = toChainedRequests(request)
            .stream()
            .map(req -> plugin.evalDataMaskPolicies(req, auditHandler()))
            .collect(Collectors.toList());
        return reduceAccessResults(request, this::handleDataMaskResult, results);
    }

    @Override
    public Collection<RangerAccessResult> isAccessAllowed(Collection<RangerAccessRequest> requests) {
        List<RangerAccessRequest> mappedRequests = requests.stream()
            .map(this::toChainedRequests)
            .flatMap(Collection::stream)
            .collect(Collectors.toList());

        return plugin.isAccessAllowed(mappedRequests, auditHandler());
    }

    @Override
    public RangerAccessResult isAccessAllowed(RangerAccessRequest request) {
        List<RangerAccessRequest> chainedRequests = toChainedRequests(request);
        if (chainedRequests.size() == 1) {
            return withPriority(plugin.isAccessAllowed(chainedRequests.get(0), auditHandler()));
        }

        return reduceAccessResults(
            request, plugin.isAccessAllowed(chainedRequests, auditHandler())
        );
    }

    @Override
    protected RangerBasePlugin buildChainedPlugin(String serviceType, String serviceName, String appId) {
        return new RangerBasePlugin(
            new RangerChainedPluginConfig(serviceType, serviceName, appId, rootPlugin.getConfig())
        );
    }

    protected abstract List<RangerAccessRequest> toChainedRequests(RangerAccessRequest request);

    protected RangerAccessResult getBaseUndeterminedResult(RangerAccessRequest request) {
        RangerAccessResult rangerAccessResult =
            new RangerAccessResult(RangerPolicy.POLICY_TYPE_ACCESS, serviceName, plugin.getServiceDef(), request);
        rangerAccessResult.setIsAccessDetermined(false);
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
        // use a single result as a return value without any reduction
        if (results.size() == 1) {
            return withPriority(results.iterator().next());
        }

        RangerAccessResult result = getBaseUndeterminedResult(request);
        // return an undetermined result in case if no mapping is found
        // to not unintentionally trigger the override of the base plugin result
        if (results.isEmpty()) {
            return result;
        }

        // set access determined if we have at least one result and start reduction
        result.setIsAccessDetermined(true);
        result.setIsAllowed(true);
        for (RangerAccessResult accessResult : results) {
            if (accessResult.getIsAccessDetermined() && !accessResult.getIsAllowed()) {
                return accessResult;
            }

            resultHandler.accept(result, accessResult);
        }

        // here we can safely override priority,
        // because we have at least one allowing result
        return withPriority(result);
    }

    protected RangerAccessResultProcessor auditHandler() {
        return new RangerDefaultAuditHandler(plugin.getConfig());
    }

    private RangerAccessResult withPriority(RangerAccessResult result) {
        result.setPolicyPriority(resultPolicyPriority);
        return result;
    }

    private int getPolicyPriority(RangerPluginConfig config) {
        boolean withHighResultPriority = config.getBoolean(
            config.getPropertyPrefix() + WITH_HIGH_PRIORITY_RESULT_CONF_SUFFIX,
                WITH_HIGH_PRIORITY_RESULT_CONF_DEFAULT);
        return withHighResultPriority
            ? RangerPolicy.POLICY_PRIORITY_OVERRIDE
            : RangerPolicy.POLICY_PRIORITY_NORMAL;
    }
}
