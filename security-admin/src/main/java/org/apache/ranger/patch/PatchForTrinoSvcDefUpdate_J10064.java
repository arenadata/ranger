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
import org.apache.log4j.Logger;
import org.apache.ranger.biz.ServiceDBStore;
import org.apache.ranger.common.RangerValidatorFactory;
import org.apache.ranger.plugin.model.RangerServiceDef;
import org.apache.ranger.plugin.model.validation.RangerServiceDefValidator;
import org.apache.ranger.plugin.model.validation.RangerValidator.Action;
import org.apache.ranger.plugin.store.EmbeddedServiceDefsUtil;
import org.apache.ranger.util.CLIUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Set;

/**
 * Adds the "execute" and "grant" access types to the Trino "schemafunction" resource
 * by augmenting only its accessTypeRestrictions in the DB-stored service-def, leaving the
 * rest of the service-def and any existing policies untouched. Skips when there is nothing to do.
 */
@Component
public class PatchForTrinoSvcDefUpdate_J10064 extends BaseLoader {
    public static final String RESOURCE_SCHEMAFUNCTION = "schemafunction";
    public static final String ACCESS_TYPE_GRANT = "grant";
    public static final String ACCESS_TYPE_EXECUTE = "execute";
    private static final Logger logger = Logger.getLogger(PatchForTrinoSvcDefUpdate_J10064.class);
    private static final String TRINO_SVC_DEF_NAME = EmbeddedServiceDefsUtil.EMBEDDED_SERVICEDEF_TRINO_NAME;
    @Autowired
    ServiceDBStore svcDBStore;
    @Autowired
    @Qualifier(value = "transactionManager")
    PlatformTransactionManager txManager;
    @Autowired
    private RangerValidatorFactory validatorFactory;

    public static void main(String[] args) {
        logger.info("main()");
        try {
            PatchForTrinoSvcDefUpdate_J10064 loader = (PatchForTrinoSvcDefUpdate_J10064) CLIUtil.getBean(PatchForTrinoSvcDefUpdate_J10064.class);
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
        // DO NOTHING
    }

    @Override
    public void printStats() {
        logger.info("PatchForTrinoSvcDefUpdate_J10064 logs ");
    }

    @Override
    public void execLoad() {
        logger.info("==> PatchForTrinoSvcDefUpdate_J10064.execLoad()");
        try {
            TransactionTemplate txTemplate = new TransactionTemplate(txManager);
            txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            try {
                txTemplate.execute(new TransactionCallback<Object>() {
                    @Override
                    public Object doInTransaction(TransactionStatus status) {
                        loadTrinoServiceDef();
                        return null;
                    }
                });
            } catch (Throwable ex) {
                logger.error("Error while updating " + TRINO_SVC_DEF_NAME + " service-def");
                throw new RuntimeException(ex.getMessage());
            }
        } catch (Exception e) {
            logger.error("Error while executing PatchForTrinoSvcDefUpdate_J10064, Error - ", e);
            throw new RuntimeException(e.getMessage());
        }
        logger.info("<== PatchForTrinoSvcDefUpdate_J10064.execLoad()");
    }

    private void loadTrinoServiceDef() {
        RangerServiceDef dbRangerServiceDef = null;
        try {
            dbRangerServiceDef = svcDBStore.getServiceDefByName(TRINO_SVC_DEF_NAME);
        } catch (Exception e) {
            logger.error("Error while reading the " + TRINO_SVC_DEF_NAME + " service-def from ranger db.", e);
        }
        if (dbRangerServiceDef == null) {
            // Not every cluster has Trino enabled (e.g. via ranger.supportedcomponents),
            // so the service-def may legitimately be absent. Skip instead of failing the upgrade.
            logger.warn("The " + TRINO_SVC_DEF_NAME + " service-def does not exist in ranger db; nothing to update. Skipping patch.");
            return;
        }
        updateTrinoSvcDef(dbRangerServiceDef);
    }

    private void updateTrinoSvcDef(RangerServiceDef dbRangerServiceDef) {
        logger.info("==> PatchForTrinoSvcDefUpdate_J10064.updateTrinoSvcDef()");
        try {
            RangerServiceDef.RangerResourceDef schemaFunctionResource = findSchemaFunctionResource(dbRangerServiceDef);
            if (!validate(schemaFunctionResource)) {
                // validate() already logged why there is nothing to do; skip without failing the upgrade.
                logger.info("<== PatchForTrinoSvcDefUpdate_J10064.updateTrinoSvcDef()");
                return;
            }

            Set<String> accessTypeRestrictions = schemaFunctionResource.getAccessTypeRestrictions();
            accessTypeRestrictions.add(ACCESS_TYPE_EXECUTE);
            accessTypeRestrictions.add(ACCESS_TYPE_GRANT);
            schemaFunctionResource.setAccessTypeRestrictions(accessTypeRestrictions);

            RangerServiceDefValidator validator = validatorFactory.getServiceDefValidator(this.svcDBStore);
            validator.validate(dbRangerServiceDef, Action.UPDATE);
            RangerServiceDef updatedSvcDef = this.svcDBStore.updateServiceDef(dbRangerServiceDef);
            if (updatedSvcDef == null) {
                logger.error("Error while updating " + TRINO_SVC_DEF_NAME + " service-def");
                throw new RuntimeException("Error while updating " + TRINO_SVC_DEF_NAME + " service-def");
            }
            logger.info(TRINO_SVC_DEF_NAME + " service-def has been updated: '" + RESOURCE_SCHEMAFUNCTION + "' now allows '" + ACCESS_TYPE_EXECUTE + "' and '" + ACCESS_TYPE_GRANT + "'");
        } catch (Exception e) {
            logger.error("Error while updating " + TRINO_SVC_DEF_NAME + " service-def", e);
            throw new RuntimeException(e);
        }
        logger.info("<== PatchForTrinoSvcDefUpdate_J10064.updateTrinoSvcDef()");
    }

    /**
     * Decides whether the 'schemafunction' resource needs (and can take) the 'execute'/'grant' update.
     * Logs the reason and returns {@code false} when there is nothing to do, so the patch skips
     * gracefully instead of failing the upgrade.
     */
    private boolean validate(RangerServiceDef.RangerResourceDef schemaFunctionResource) {
        if (!isResourcePresent(schemaFunctionResource)) {
            logger.warn("The '" + RESOURCE_SCHEMAFUNCTION + "' resource does not exist in the " + TRINO_SVC_DEF_NAME + " service-def; nothing to update. Skipping patch.");
            return false;
        }
        if (!hasAccessTypeRestrictions(schemaFunctionResource)) {
            // An empty/absent restriction set means every access type (including 'execute' and 'grant')
            // is already allowed on this resource. Adding entries here would instead RESTRICT it, so skip.
            logger.info("The '" + RESOURCE_SCHEMAFUNCTION + "' resource has no access-type restrictions; all access types are already allowed; nothing to update");
            return false;
        }
        if (allowsExecuteAndGrant(schemaFunctionResource)) {
            logger.info("The '" + RESOURCE_SCHEMAFUNCTION + "' resource already allows '" + ACCESS_TYPE_EXECUTE + "' and '" + ACCESS_TYPE_GRANT + "'; nothing to update");
            return false;
        }
        return true;
    }

    private RangerServiceDef.RangerResourceDef findSchemaFunctionResource(RangerServiceDef serviceDef) {
        for (RangerServiceDef.RangerResourceDef resourceDef : serviceDef.getResources()) {
            if (RESOURCE_SCHEMAFUNCTION.equals(resourceDef.getName())) {
                return resourceDef;
            }
        }
        return null;
    }

    private boolean isResourcePresent(RangerServiceDef.RangerResourceDef resourceDef) {
        return resourceDef != null;
    }

    private boolean hasAccessTypeRestrictions(RangerServiceDef.RangerResourceDef resourceDef) {
        return CollectionUtils.isNotEmpty(resourceDef.getAccessTypeRestrictions());
    }

    private boolean allowsExecuteAndGrant(RangerServiceDef.RangerResourceDef resourceDef) {
        Set<String> accessTypeRestrictions = resourceDef.getAccessTypeRestrictions();
        return accessTypeRestrictions.contains(ACCESS_TYPE_EXECUTE) && accessTypeRestrictions.contains(ACCESS_TYPE_GRANT);
    }
}
