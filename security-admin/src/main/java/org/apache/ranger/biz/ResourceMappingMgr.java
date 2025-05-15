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

package org.apache.ranger.biz;

import java.util.List;
import java.util.stream.Collectors;
import org.apache.ranger.db.RangerDaoManager;
import org.apache.ranger.entity.ResourceMapping;
import org.apache.ranger.entity.XXResourceMappingDiff;
import org.apache.ranger.view.VXResourceMapping;
import org.apache.ranger.view.VXResourceMappingDiff;
import org.apache.ranger.view.VXResourceMappingDiffs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ResourceMappingMgr {

    private final RangerDaoManager rangerDaoManager;

    @Autowired
    public ResourceMappingMgr(RangerDaoManager rangerDaoManager) {
        this.rangerDaoManager = rangerDaoManager;
    }

    public VXResourceMappingDiffs getDiffsNewerThan(String sourceService, String targetService, long diffId) {
        List<XXResourceMappingDiff> diffs = rangerDaoManager.getXXResourceMappingDiff()
            .getDiffsNewerThan(sourceService, targetService, diffId);
        return toDto(diffs);
    }

    public VXResourceMappingDiffs getAllDiffs(String sourceService, String targetService) {
        List<XXResourceMappingDiff> diffs = rangerDaoManager.getXXResourceMappingDiff()
            .getAllDiffs(sourceService, targetService);
        return toDto(diffs);
    }

    private VXResourceMappingDiffs toDto(List<XXResourceMappingDiff> diffs) {
        List<VXResourceMappingDiff> diffDtos = diffs.stream()
            .map(this::toDto)
            .collect(Collectors.toList());
        return new VXResourceMappingDiffs(diffDtos);
    }

    private VXResourceMappingDiff toDto(XXResourceMappingDiff diff) {
        return new VXResourceMappingDiff(
            toDto(diff.getOldEntity()),
            toDto(diff.getNewEntity()),
            diff.getEntityType(),
            diff.getDiffType(),
            diff.getId(),
            diff.getSourceService(),
            diff.getTargetService()
        );
    }

    private VXResourceMapping toDto(ResourceMapping mapping) {
        return mapping == null ? null : new VXResourceMapping(
            mapping.getName(),
            mapping.getLocation()
        );
    }
}
