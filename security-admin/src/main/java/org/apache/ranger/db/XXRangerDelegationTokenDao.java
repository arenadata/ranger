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
import org.apache.ranger.entity.XXRangerDelegationToken;
import org.springframework.stereotype.Service;

@Service
public class XXRangerDelegationTokenDao extends BaseDao<XXRangerDelegationToken> {

    public XXRangerDelegationTokenDao(RangerDaoManagerBase daoManager) {
        super(daoManager);
    }

    @Override
    public XXRangerDelegationToken create(XXRangerDelegationToken obj) {
        obj.setCreateTime(DateUtil.getUTCDate());
        return super.create(obj);
    }

    public XXRangerDelegationToken findBySequenceNumber(int sequenceNumber) {
        try {
            return getEntityManager()
                    .createQuery("SELECT obj FROM XXRangerDelegationToken obj WHERE obj.sequenceNumber = :seqNum", tClass)
                    .setParameter("seqNum", sequenceNumber)
                    .getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    public List<XXRangerDelegationToken> findAll() {
        return getEntityManager()
                .createQuery("SELECT obj FROM XXRangerDelegationToken obj", tClass)
                .getResultList();
    }

    public void deleteBySequenceNumber(int sequenceNumber) {
        getEntityManager()
                .createQuery("DELETE FROM XXRangerDelegationToken obj WHERE obj.sequenceNumber = :seqNum")
                .setParameter("seqNum", sequenceNumber)
                .executeUpdate();
    }

    public void updateRenewDate(int sequenceNumber, long renewDate) {
        getEntityManager()
                .createQuery("UPDATE XXRangerDelegationToken obj SET obj.renewDate = :renewDate WHERE obj.sequenceNumber = :seqNum")
                .setParameter("renewDate", renewDate)
                .setParameter("seqNum", sequenceNumber)
                .executeUpdate();
    }
}
