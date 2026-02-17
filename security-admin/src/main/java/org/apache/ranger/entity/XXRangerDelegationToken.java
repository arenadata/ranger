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

package org.apache.ranger.entity;

import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Lob;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;

@Entity
@Table(name = "x_ranger_delegation_token")
public class XXRangerDelegationToken implements java.io.Serializable {
    private static final long serialVersionUID = 1L;

    @Id
    @SequenceGenerator(name = "X_RANGER_DELEGATION_TOKEN_SEQ", sequenceName = "X_RANGER_DELEGATION_TOKEN_SEQ", allocationSize = 1)
    @GeneratedValue(strategy = GenerationType.AUTO, generator = "X_RANGER_DELEGATION_TOKEN_SEQ")
    @Column(name = "id")
    protected Long id;

    @Column(name = "sequence_number", nullable = false, unique = true)
    protected Integer sequenceNumber;

    @Column(name = "owner", nullable = false)
    protected String owner;

    @Column(name = "renewer")
    protected String renewer;

    @Column(name = "real_user")
    protected String realUser;

    @Column(name = "issue_date", nullable = false)
    protected Long issueDate;

    @Column(name = "max_date", nullable = false)
    protected Long maxDate;

    @Column(name = "renew_date", nullable = false)
    protected Long renewDate;

    @Column(name = "master_key_id", nullable = false)
    protected Integer masterKeyId;

    @Lob
    @Column(name = "token_password", nullable = false)
    protected byte[] tokenPassword;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "create_time")
    protected Date createTime;

    public XXRangerDelegationToken() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Integer getSequenceNumber() {
        return sequenceNumber;
    }

    public void setSequenceNumber(Integer sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getRenewer() {
        return renewer;
    }

    public void setRenewer(String renewer) {
        this.renewer = renewer;
    }

    public String getRealUser() {
        return realUser;
    }

    public void setRealUser(String realUser) {
        this.realUser = realUser;
    }

    public Long getIssueDate() {
        return issueDate;
    }

    public void setIssueDate(Long issueDate) {
        this.issueDate = issueDate;
    }

    public Long getMaxDate() {
        return maxDate;
    }

    public void setMaxDate(Long maxDate) {
        this.maxDate = maxDate;
    }

    public Long getRenewDate() {
        return renewDate;
    }

    public void setRenewDate(Long renewDate) {
        this.renewDate = renewDate;
    }

    public Integer getMasterKeyId() {
        return masterKeyId;
    }

    public void setMasterKeyId(Integer masterKeyId) {
        this.masterKeyId = masterKeyId;
    }

    public byte[] getTokenPassword() {
        return tokenPassword;
    }

    public void setTokenPassword(byte[] tokenPassword) {
        this.tokenPassword = tokenPassword;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    @Override
    public String toString() {
        return "XXRangerDelegationToken [id=" + id + ", sequenceNumber=" + sequenceNumber
                + ", owner=" + owner + ", renewer=" + renewer + ", issueDate=" + issueDate
                + ", maxDate=" + maxDate + ", renewDate=" + renewDate + ", masterKeyId=" + masterKeyId + "]";
    }
}
