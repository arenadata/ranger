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

package org.apache.ranger.plugin.policyengine;

import org.apache.ranger.audit.model.AuthzAuditEvent;
import org.apache.ranger.plugin.audit.RangerDefaultAuditHandler;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerServiceDef;
import org.apache.ranger.plugin.model.RangerServiceDef.RangerResourceDef;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class TestRangerAccessResourceImpl {
    @Test
    public void testGetAsStringWithoutServiceDef() {
        RangerAccessResourceImpl resource = new RangerAccessResourceImpl();
        resource.setValue("database", "ranger_cache_test");

        assertEquals("database=ranger_cache_test", resource.getAsString());
        assertEquals("database=ranger_cache_test", resource.getAsString());
        assertEquals(Collections.singletonMap("database", "ranger_cache_test"), resource.getAsMap());
        assertNull(resource.getServiceDef());
        assertNull(resource.getCacheKey());
        assertNull(resource.getLeafName());
    }

    @Test
    public void testFallbackHasStableResourceNameOrder() {
        Map<String, Object> elements = new LinkedHashMap<>();
        elements.put("table", "secret_table");
        elements.put("database", "ranger_cache_test");
        elements.put("column", "id");

        Map<String, Object> reversedElements = new LinkedHashMap<>();
        reversedElements.put("column", "id");
        reversedElements.put("database", "ranger_cache_test");
        reversedElements.put("table", "secret_table");

        RangerAccessResourceImpl resource = new RangerAccessResourceImpl(elements);
        RangerAccessResourceImpl reversed = new RangerAccessResourceImpl(reversedElements);

        assertEquals("column=id, database=ranger_cache_test, table=secret_table", resource.getAsString());
        assertEquals(resource.getAsString(), reversed.getAsString());
        assertEquals(Arrays.asList("table", "database", "column"), new ArrayList<>(elements.keySet()));
    }

    @Test
    public void testFallbackIncludesResourceNames() {
        RangerAccessResourceImpl database = new RangerAccessResourceImpl();
        database.setValue("database", "same_name");
        RangerAccessResourceImpl table = new RangerAccessResourceImpl();
        table.setValue("table", "same_name");

        assertNotEquals(database.getAsString(), table.getAsString());
    }

    @Test
    public void testFallbackSupportsCollectionValues() {
        RangerAccessResourceImpl resource = new RangerAccessResourceImpl();
        resource.setValue("url", Arrays.asList("hdfs://warehouse/db", "hdfs://warehouse/db/"));

        assertEquals("url=[hdfs://warehouse/db, hdfs://warehouse/db/]", resource.getAsString());
    }

    @Test
    public void testFallbackIgnoresNullNamesAndValues() {
        Map<String, Object> elements = new HashMap<>();
        elements.put(null, "ignored");
        elements.put("table", null);
        elements.put("database", "ranger_cache_test");

        RangerAccessResourceImpl resource = new RangerAccessResourceImpl(elements);

        assertEquals("database=ranger_cache_test", resource.getAsString());
        assertEquals(3, resource.getAsMap().size());
    }

    @Test
    public void testEmptyResourceRetainsNullDescription() {
        assertNull(new RangerAccessResourceImpl().getAsString());
        assertNull(new RangerAccessResourceImpl(Collections.emptyMap()).getAsString());

        Map<String, Object> elements = new HashMap<>();
        elements.put(null, "ignored");
        elements.put("database", null);

        assertNull(new RangerAccessResourceImpl(elements).getAsString());
    }

    @Test
    public void testSetValueInvalidatesFallbackDescription() {
        RangerAccessResourceImpl resource = new RangerAccessResourceImpl();
        resource.setValue("database", "old_db");
        assertEquals("database=old_db", resource.getAsString());

        resource.setValue("database", "new_db");
        resource.setValue("table", "secret_table");
        assertEquals("database=new_db, table=secret_table", resource.getAsString());

        resource.setValue("table", null);
        assertEquals("database=new_db", resource.getAsString());

        resource.setValue("database", null);
        assertNull(resource.getAsString());
    }

    @Test
    public void testServiceDefArrivalAndRemovalInvalidateDescription() {
        RangerAccessResourceImpl resource = new RangerAccessResourceImpl();
        resource.setValue("column", "id");
        resource.setValue("table", "secret_table");
        resource.setValue("database", "ranger_cache_test");
        String fallback = "column=id, database=ranger_cache_test, table=secret_table";
        assertEquals(fallback, resource.getAsString());

        resource.setServiceDef(serviceDef("database", "table", "column"));
        assertEquals("ranger_cache_test/secret_table/id", resource.getAsString());
        assertEquals("database=ranger_cache_test/table=secret_table/column=id", resource.getCacheKey());
        assertEquals("column", resource.getLeafName());

        resource.setServiceDef(null);
        assertEquals(fallback, resource.getAsString());
        assertNull(resource.getCacheKey());
        assertNull(resource.getLeafName());
    }

    @Test
    public void testExistingServiceDefFormatIsPreserved() {
        RangerAccessResourceImpl resource = new RangerAccessResourceImpl();
        resource.setValue("database", "ranger_cache_test");
        resource.setValue("table", "secret_table");
        resource.setValue("not_in_service_def", "ignored");
        resource.setServiceDef(serviceDef("database", null, "missing", "table"));

        assertEquals("ranger_cache_test/secret_table", resource.getAsString());
        assertEquals("database=ranger_cache_test/table=secret_table", resource.getCacheKey());

        resource.setValue("table", "another_table");
        assertEquals("ranger_cache_test/another_table", resource.getAsString());
        assertEquals("database=ranger_cache_test/table=another_table", resource.getCacheKey());
    }

    @Test
    public void testExistingServiceDefWithoutMatchingFieldsDoesNotUseFallback() {
        RangerAccessResourceImpl resource = new RangerAccessResourceImpl();
        resource.setValue("database", "ranger_cache_test");
        resource.setServiceDef(serviceDef("path"));
        assertNull(resource.getAsString());

        resource.setServiceDef(serviceDef());
        assertNull(resource.getAsString());
    }

    @Test
    public void testReadOnlyResourceUsesFallback() {
        RangerAccessResourceImpl resource = new RangerAccessResourceImpl();
        resource.setValue("database", "ranger_cache_test");

        assertEquals("database=ranger_cache_test", resource.getReadOnlyCopy().getAsString());
    }

    @Test
    public void testAuditEventUsesFallbackWithoutChangingAccessDecision() {
        RangerAccessResourceImpl resource = new RangerAccessResourceImpl();
        resource.setValue("database", "ranger_cache_test");
        RangerAccessRequest request = new RangerAccessRequestImpl(resource, "create", "spark_user6", Collections.emptySet(), null);
        RangerAccessResult result = new RangerAccessResult(RangerPolicy.POLICY_TYPE_ACCESS, "spark_test", null, request);
        result.setIsAllowed(false);
        result.setIsAudited(true);

        AuthzAuditEvent event = new RangerDefaultAuditHandler().getAuthzEvents(result);

        assertNotNull(event);
        assertEquals("database=ranger_cache_test", event.getResourcePath());
        assertEquals(0, event.getAccessResult());
        assertFalse(result.getIsAllowed());
    }

    private static RangerServiceDef serviceDef(String... names) {
        List<RangerResourceDef> resources = new ArrayList<>();

        for (String name : names) {
            RangerResourceDef resource = null;
            if (name != null) {
                resource = new RangerResourceDef();
                resource.setName(name);
            }
            resources.add(resource);
        }

        RangerServiceDef serviceDef = new RangerServiceDef();
        serviceDef.setResources(resources);

        return serviceDef;
    }
}
