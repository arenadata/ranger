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

package org.apache.ranger.db;

import java.util.List;

import javax.persistence.NoResultException;

import org.apache.ranger.common.DateUtil;
import org.apache.ranger.common.db.BaseDao;
import org.apache.ranger.entity.XXRangerDTMasterKey;
import org.springframework.stereotype.Service;

@Service
public class XXRangerDTMasterKeyDao extends BaseDao<XXRangerDTMasterKey> {

    public XXRangerDTMasterKeyDao(RangerDaoManagerBase daoManager) {
        super(daoManager);
    }

    @Override
    public XXRangerDTMasterKey create(XXRangerDTMasterKey obj) {
        obj.setCreateTime(DateUtil.getUTCDate());
        return super.create(obj);
    }

    public XXRangerDTMasterKey findByKeyId(int keyId) {
        try {
            return getEntityManager()
                    .createQuery("SELECT obj FROM XXRangerDTMasterKey obj WHERE obj.keyId = :keyId", tClass)
                    .setParameter("keyId", keyId)
                    .getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    public List<XXRangerDTMasterKey> findAll() {
        return getEntityManager()
                .createQuery("SELECT obj FROM XXRangerDTMasterKey obj ORDER BY obj.keyId", tClass)
                .getResultList();
    }

    public void deleteByKeyId(int keyId) {
        getEntityManager()
                .createQuery("DELETE FROM XXRangerDTMasterKey obj WHERE obj.keyId = :keyId")
                .setParameter("keyId", keyId)
                .executeUpdate();
    }
}
