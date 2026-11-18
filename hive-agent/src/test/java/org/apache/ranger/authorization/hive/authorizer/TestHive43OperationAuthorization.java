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

package org.apache.ranger.authorization.hive.authorizer;

import org.apache.hadoop.hive.ql.security.authorization.plugin.HiveAccessControlException;
import org.apache.hadoop.hive.ql.security.authorization.plugin.HiveOperationType;
import org.apache.hadoop.hive.ql.security.authorization.plugin.HivePrivilegeObject;
import org.apache.hadoop.hive.ql.security.authorization.plugin.HivePrivilegeObject.HivePrivilegeObjectType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

public class TestHive43OperationAuthorization {
    @Test
    public void unsupportedCatalogOperationsFailClosedWithoutPrivilegeObjects() {
        RangerHiveAuthorizer authorizer = mock(RangerHiveAuthorizer.class, CALLS_REAL_METHODS);
        HiveOperationType[] operations = {
                HiveOperationType.CREATECATALOG, HiveOperationType.DROPCATALOG,
                HiveOperationType.ALTERCATALOG_LOCATION, HiveOperationType.ALTERCATALOG_PROPERTIES,
                HiveOperationType.SHOWCATALOGS, HiveOperationType.DESCCATALOG, HiveOperationType.SWITCHCATALOG
        };
        for (HiveOperationType operation : operations) {
            HiveAccessControlException failure = assertThrows(HiveAccessControlException.class,
                    () -> authorizer.checkPrivileges(operation, Collections.emptyList(), Collections.emptyList(), null));
            assertTrue(failure.getMessage().contains("Unsupported Hive catalog operation " + operation));
        }
    }

    @Test
    public void tableMetadataMutationsRequireAlterOnInputAndOutputTable() throws Exception {
        RangerHiveAuthorizer authorizer = mock(RangerHiveAuthorizer.class, CALLS_REAL_METHODS);
        HivePrivilegeObject table = new HivePrivilegeObject(HivePrivilegeObjectType.TABLE_OR_VIEW, "db", "table");
        Method accessType = RangerHiveAuthorizer.class.getDeclaredMethod("getAccessType",
                HivePrivilegeObject.class, HiveOperationType.class, HiveObjectType.class, boolean.class);
        accessType.setAccessible(true);
        HiveOperationType[] operations = {
                HiveOperationType.ALTERTABLE_DROPCOL, HiveOperationType.ALTERTABLE_DROP_COL_STATS,
                HiveOperationType.ALTERTABLE_RENAMEBRANCH, HiveOperationType.ALTERTABLE_REPLACESNAPSHOTREF,
                HiveOperationType.ALTERTABLE_SET_WRITE_ORDER
        };
        for (HiveOperationType operation : operations) {
            assertEquals(HiveAccessType.ALTER, accessType.invoke(authorizer, table, operation, HiveObjectType.TABLE, true));
            assertEquals(HiveAccessType.ALTER, accessType.invoke(authorizer, table, operation, HiveObjectType.TABLE, false));
        }
    }
}
