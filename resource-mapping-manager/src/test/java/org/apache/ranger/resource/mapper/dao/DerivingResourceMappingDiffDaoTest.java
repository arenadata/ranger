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

package org.apache.ranger.resource.mapper.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Optional;
import org.apache.ranger.resource.mapper.hive.event.MetastoreEntityDiffFactory;
import org.apache.ranger.resource.mapper.hive.model.HiveEntityType;
import org.apache.ranger.resource.mapper.model.ResourceMapping;
import org.apache.ranger.resource.mapper.model.ResourceMappingDiff;
import org.apache.ranger.resource.mapper.starrocks.StarRocksResourceMappingDeriver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class DerivingResourceMappingDiffDaoTest {
    private static final String HDFS_LOCATION = "hdfs://nn:8020/warehouse/db1.db/t1";

    private ResourceMappingDiffDao delegate;
    private DerivingResourceMappingDiffDao dao;

    @BeforeEach
    void setUp() {
        delegate = mock(ResourceMappingDiffDao.class);
        dao = new DerivingResourceMappingDiffDao(
            delegate,
            Collections.singletonList(
                new StarRocksResourceMappingDeriver(Collections.singletonList("hive_catalog")))
        );
    }

    @Test
    void testInsertDiffInsertsOriginalAndDerived() {
        ResourceMappingDiff diff = MetastoreEntityDiffFactory.createEntity(
            "hive.db1.t1", HiveEntityType.TABLE, HDFS_LOCATION, 1L);

        dao.insertDiff(diff);

        InOrder inOrder = inOrder(delegate);
        inOrder.verify(delegate).insertDiff(diff);
        inOrder.verify(delegate).insertDiff(ResourceMappingDiff.builder()
            .id(1L)
            .entityType(HiveEntityType.TABLE.name())
            .diffType(diff.getDiffType())
            .sourceService("hive")
            .targetService("starrocks")
            .oldEntity(ResourceMapping.builder().name("hive.db1.t1").location("hive_catalog.db1.t1").build())
            .build());
        verifyNoMoreInteractions(delegate);
    }

    @Test
    void testDeleteDiffsForDeletesOriginalAndDerived() {
        ResourceMapping mapping = ResourceMapping.builder().name("hive.db1.t1").location(HDFS_LOCATION).build();

        dao.deleteDiffsFor(mapping);

        verify(delegate).deleteDiffsFor(mapping);
        verify(delegate).deleteDiffsFor(
            ResourceMapping.builder().name("hive.db1.t1").location("hive_catalog.db1.t1").build());
        verifyNoMoreInteractions(delegate);
    }

    @Test
    void testOtherCallsAreDelegated() throws Exception {
        when(delegate.getLatestExternalDiffId("hive")).thenReturn(Optional.of(5L));

        assertEquals(Optional.of(5L), dao.getLatestExternalDiffId("hive"));
        dao.deleteAllDiffs();
        verify(delegate).deleteAllDiffs();
    }
}
