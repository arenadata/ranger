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
package org.apache.ranger.patch;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.ranger.biz.ServiceDBStore;
import org.apache.ranger.common.RangerValidatorFactory;
import org.apache.ranger.plugin.model.RangerServiceDef;
import org.apache.ranger.plugin.model.RangerServiceDef.RangerServiceConfigDef;
import org.apache.ranger.plugin.model.validation.RangerServiceDefValidator;
import org.apache.ranger.plugin.model.validation.RangerValidator.Action;
import org.apache.ranger.plugin.store.EmbeddedServiceDefsUtil;
import org.apache.ranger.util.CLIUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class PatchForNestedstructureServiceDefUpdate_J10065 extends BaseLoader {
    private static final Logger logger = LoggerFactory.getLogger(PatchForNestedstructureServiceDefUpdate_J10065.class);

    private static final String SERVICE_DEF_NAME            = EmbeddedServiceDefsUtil.EMBEDDED_SERVICEDEF_NESTEDSTRUCTURE_NAME;
    private static final String POLICY_DOWNLOAD_AUTH_GROUPS = "policy.download.auth.groups";

    @Autowired
    ServiceDBStore svcDBStore;

    @Autowired
    private RangerValidatorFactory validatorFactory;

    public static void main(String[] args) {
        logger.info("main()");

        try {
            PatchForNestedstructureServiceDefUpdate_J10065 loader = (PatchForNestedstructureServiceDefUpdate_J10065) CLIUtil.getBean(PatchForNestedstructureServiceDefUpdate_J10065.class);

            loader.init();

            while (loader.isMoreToProcess()) {
                loader.load();
            }

            logger.info("Load complete. Exiting!!!");

            System.exit(0);
        } catch (Exception e) {
            logger.error("Error loading", e);

            System.exit(1);
        }
    }

    @Override
    public void init() throws Exception {
        // Do Nothing
    }

    @Override
    public void execLoad() {
        logger.info("==> PatchForNestedstructureServiceDefUpdate_J10065.execLoad()");

        try {
            updateNestedstructureServiceDef();
        } catch (Exception e) {
            logger.error("PatchForNestedstructureServiceDefUpdate_J10065.execLoad(): failed", e);
            System.exit(1);
        }

        logger.info("<== PatchForNestedstructureServiceDefUpdate_J10065.execLoad()");
    }

    @Override
    public void printStats() {
        logger.info("PatchForNestedstructureServiceDefUpdate_J10065");
    }

    private void updateNestedstructureServiceDef() throws Exception {
        logger.info("==> PatchForNestedstructureServiceDefUpdate_J10065.updateNestedstructureServiceDef()");

        RangerServiceDef embeddedServiceDef = EmbeddedServiceDefsUtil.instance().getEmbeddedServiceDef(SERVICE_DEF_NAME);

        if (embeddedServiceDef == null) {
            throw new IllegalStateException("Embedded service-def does not exist: " + SERVICE_DEF_NAME);
        }

        RangerServiceConfigDef embeddedConfig = findConfigByName(embeddedServiceDef.getConfigs(), POLICY_DOWNLOAD_AUTH_GROUPS);

        if (embeddedConfig == null) {
            throw new IllegalStateException("Embedded service-def " + SERVICE_DEF_NAME + " does not contain config: " + POLICY_DOWNLOAD_AUTH_GROUPS);
        }

        RangerServiceDef dbServiceDef = svcDBStore.getServiceDefByName(SERVICE_DEF_NAME);

        if (dbServiceDef == null) {
            logger.info("Service-def [{}] does not exist in Ranger DB. Skipping.", SERVICE_DEF_NAME);
            return;
        }

        if (findConfigByName(dbServiceDef.getConfigs(), POLICY_DOWNLOAD_AUTH_GROUPS) != null) {
            logger.info("Config [{}] already exists in service-def [{}]. Skipping.", POLICY_DOWNLOAD_AUTH_GROUPS, SERVICE_DEF_NAME);
            return;
        }

        List<RangerServiceConfigDef> configs = CollectionUtils.isEmpty(dbServiceDef.getConfigs()) ? new ArrayList<>() : new ArrayList<>(dbServiceDef.getConfigs());
        RangerServiceConfigDef       config  = copyConfig(embeddedConfig);

        if (isItemIdUsed(configs, config.getItemId())) {
            config.setItemId(getNextItemId(configs));
        }

        configs.add(config);
        dbServiceDef.setConfigs(configs);

        RangerServiceDefValidator validator = validatorFactory.getServiceDefValidator(svcDBStore);

        validator.validate(dbServiceDef, Action.UPDATE);
        svcDBStore.updateServiceDef(dbServiceDef);

        logger.info("Added config [{}] to service-def [{}]", POLICY_DOWNLOAD_AUTH_GROUPS, SERVICE_DEF_NAME);
        logger.info("<== PatchForNestedstructureServiceDefUpdate_J10065.updateNestedstructureServiceDef()");
    }

    private RangerServiceConfigDef copyConfig(RangerServiceConfigDef config) {
        return new RangerServiceConfigDef(config.getItemId(), config.getName(), config.getType(), config.getSubType(), config.getMandatory(), config.getDefaultValue(),
                config.getValidationRegEx(), config.getValidationMessage(), config.getUiHint(), config.getLabel(), config.getDescription(), config.getRbKeyLabel(),
                config.getRbKeyDescription(), config.getRbKeyValidationMessage());
    }

    private RangerServiceConfigDef findConfigByName(List<RangerServiceConfigDef> configs, String name) {
        RangerServiceConfigDef ret = null;

        if (CollectionUtils.isNotEmpty(configs)) {
            for (RangerServiceConfigDef config : configs) {
                if (config != null && StringUtils.equals(config.getName(), name)) {
                    ret = config;
                    break;
                }
            }
        }

        return ret;
    }

    private boolean isItemIdUsed(List<RangerServiceConfigDef> configs, Long itemId) {
        boolean ret = false;

        if (itemId != null && CollectionUtils.isNotEmpty(configs)) {
            for (RangerServiceConfigDef config : configs) {
                if (config != null && itemId.equals(config.getItemId())) {
                    ret = true;
                    break;
                }
            }
        }

        return ret;
    }

    private Long getNextItemId(List<RangerServiceConfigDef> configs) {
        long ret = 1;

        if (CollectionUtils.isNotEmpty(configs)) {
            for (RangerServiceConfigDef config : configs) {
                if (config != null && config.getItemId() != null) {
                    ret = Math.max(ret, config.getItemId() + 1);
                }
            }
        }

        return ret;
    }
}
