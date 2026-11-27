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

import org.apache.ranger.plugin.policyengine.RangerAccessResourceImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The owner of a Hive resource created with an owner source is the owner of that source, read on demand.
 */
public class RangerHiveResourceTest {

    @Test
    public void testOwnerIsReadFromTheOwnerSourceOnDemand() {
        CountingOwnerResource ownerSource = new CountingOwnerResource("alice");
        RangerHiveResource resource = new RangerHiveResource(HiveObjectType.COLUMN, "db1", "t1", "c1", ownerSource);

        assertEquals(0, ownerSource.ownerReads);
        assertEquals("db1", resource.getDatabase());
        assertEquals("t1", resource.getTable());
        assertEquals("c1", resource.getColumn());
        assertEquals("alice", resource.getOwnerUser());
        assertEquals("alice", resource.getReadOnlyCopy().getOwnerUser());
    }

    @Test
    public void testOwnerSetOnTheResourceWins() {
        CountingOwnerResource ownerSource = new CountingOwnerResource("alice");
        RangerHiveResource resource = new RangerHiveResource(HiveObjectType.TABLE, "db1", "t1", null, ownerSource);

        resource.setOwnerUser("bob");

        assertEquals("bob", resource.getOwnerUser());
        assertEquals(0, ownerSource.ownerReads);
    }

    @Test
    public void testOwnerSourceWithoutOwner() {
        RangerHiveResource resource = new RangerHiveResource(HiveObjectType.DATABASE, "db1", null, null, new RangerAccessResourceImpl());

        assertNull(resource.getOwnerUser());
    }

    @Test
    public void testResourceWithoutOwnerSource() {
        RangerHiveResource resource = new RangerHiveResource(HiveObjectType.TABLE, "db1", "t1");
        assertNull(resource.getOwnerUser());

        resource.setOwnerUser("carol");
        assertEquals("carol", resource.getOwnerUser());
    }

    private static final class CountingOwnerResource extends RangerAccessResourceImpl {
        private final String owner;
        private int ownerReads;

        private CountingOwnerResource(String owner) {
            this.owner = owner;
        }

        @Override
        public String getOwnerUser() {
            ownerReads++;
            return owner;
        }
    }
}
