/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ranger.db;

import java.util.Collections;
import java.util.List;
import javax.persistence.NoResultException;
import org.apache.ranger.common.db.BaseDao;
import org.apache.ranger.entity.XXResourceMappingDiff;
import org.springframework.stereotype.Service;

@Service
public class XXResourceMappingDiffDao extends BaseDao<XXResourceMappingDiff> {
    public XXResourceMappingDiffDao(RangerDaoManagerBase daoManager) {
        super(daoManager);
    }

    public List<XXResourceMappingDiff> getDiffsNewerThan(String sourceService, String targetService, long diffId) {
        try {
            return getEntityManager()
                .createNamedQuery("XXMetastoreMappingDiff.getDiffsNewerThan",
                    XXResourceMappingDiff.class)
                .setParameter("diffId", diffId)
                .setParameter("sourceService", sourceService)
                .setParameter("targetService", targetService)
                .getResultList();
        } catch (NoResultException e) {
            return Collections.emptyList();
        }
    }

    public List<XXResourceMappingDiff> getAllDiffs(String sourceService, String targetService) {
        try {
            return getEntityManager()
                .createNamedQuery("XXMetastoreMappingDiff.getAllDiffs",
                    XXResourceMappingDiff.class)
                .setParameter("sourceService", sourceService)
                .setParameter("targetService", targetService)
                .getResultList();
        } catch (NoResultException e) {
            return Collections.emptyList();
        }
    }
}

