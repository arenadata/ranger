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

import org.apache.ranger.biz.ServiceDBStore;
import org.apache.ranger.common.RangerValidatorFactory;
import org.apache.ranger.plugin.model.RangerServiceDef;
import org.apache.ranger.plugin.model.RangerServiceDef.RangerServiceConfigDef;
import org.apache.ranger.plugin.model.validation.RangerServiceDefValidator;
import org.apache.ranger.plugin.model.validation.RangerValidator.Action;
import org.apache.ranger.plugin.store.EmbeddedServiceDefsUtil;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Collections;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class TestPatchForNestedstructureServiceDefUpdate_J10065 {
    private PatchForNestedstructureServiceDefUpdate_J10065 patch;
    private ServiceDBStore store;
    private RangerServiceDefValidator validator;
    private RangerServiceDef serviceDef;

    @Before
    public void setUp() throws Exception {
        patch = new PatchForNestedstructureServiceDefUpdate_J10065();
        store = mock(ServiceDBStore.class);
        validator = mock(RangerServiceDefValidator.class);
        RangerValidatorFactory factory = mock(RangerValidatorFactory.class);
        when(factory.getServiceDefValidator(store)).thenReturn(validator);
        patch.svcDBStore = store;
        Field field = PatchForNestedstructureServiceDefUpdate_J10065.class.getDeclaredField("validatorFactory");
        field.setAccessible(true);
        field.set(patch, factory);
        serviceDef = new RangerServiceDef();
        serviceDef.setName("nestedstructure");
        when(store.getServiceDefByName("nestedstructure")).thenReturn(serviceDef);
    }

    @Test
    public void addsDownloadGroupsAndDoesNotChangeExistingConfigs() throws Exception {
        RangerServiceConfigDef existing = new RangerServiceConfigDef();
        existing.setName("policy.download.auth.users");
        existing.setItemId(1L);
        existing.setDefaultValue("hive");
        serviceDef.setConfigs(Collections.singletonList(existing));

        patch.execLoad();

        assertEquals(2, serviceDef.getConfigs().size());
        assertSame(existing, serviceDef.getConfigs().get(0));
        assertEquals("hive", existing.getDefaultValue());
        assertEquals("policy.download.auth.groups", serviceDef.getConfigs().get(1).getName());
        verify(validator).validate(serviceDef, Action.UPDATE);
        verify(store).updateServiceDef(serviceDef);
    }

    @Test
    public void repeatedMigrationIsIdempotent() throws Exception {
        patch.execLoad();
        patch.execLoad();
        assertEquals(1, serviceDef.getConfigs().size());
        verify(store, times(1)).updateServiceDef(serviceDef);
    }

    @Test
    public void allocatesUnusedItemId() throws Exception {
        RangerServiceDef embedded = EmbeddedServiceDefsUtil.instance().getEmbeddedServiceDef("nestedstructure");
        RangerServiceConfigDef groupConfig = embedded.getConfigs().stream()
                .filter(config -> "policy.download.auth.groups".equals(config.getName())).findFirst().get();
        RangerServiceConfigDef existing = new RangerServiceConfigDef();
        existing.setName("custom.setting");
        existing.setItemId(groupConfig.getItemId());
        serviceDef.setConfigs(Collections.singletonList(existing));

        patch.execLoad();

        assertEquals(Long.valueOf(existing.getItemId() + 1), serviceDef.getConfigs().get(1).getItemId());
        assertSame(existing, serviceDef.getConfigs().get(0));
    }

    @Test
    public void missingServiceDefIsSkipped() throws Exception {
        when(store.getServiceDefByName("nestedstructure")).thenReturn(null);
        patch.execLoad();
        verify(store, never()).updateServiceDef(any(RangerServiceDef.class));
    }
}
