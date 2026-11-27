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

package org.apache.ranger.hive.chained.ozone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.apache.ranger.plugin.model.ResourceMapping;
import org.junit.jupiter.api.Test;

/**
 * The location of an RMM mapping becomes the Ozone path; the Hive owner of the mapping must survive it.
 */
public class OzoneHiveMappingFetcherTest {
    private final OzoneHiveMappingFetcher fetcher = new OzoneHiveMappingFetcher(null, null, -1L, -1L, "ozone");

    @Test
    public void testOwnerSurvivesTheLocationConversion() {
        ResourceMapping mapping = fetcher.transformMapping(
            new ResourceMapping("hive.db1.t1", "ofs://om1/vol1/bucket1/warehouse/db1.db/t1", "alice"));

        assertEquals("hive.db1.t1", mapping.getName());
        assertEquals("/vol1/bucket1/warehouse/db1.db/t1", mapping.getLocation());
        assertEquals("alice", mapping.getOwner());
    }

    @Test
    public void testMappingWithoutOwner() {
        assertNull(fetcher.transformMapping(new ResourceMapping("hive.db1", "ofs://om1/vol1/bucket1/warehouse/db1.db")).getOwner());
    }
}
