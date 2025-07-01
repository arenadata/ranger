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

import java.io.Serializable;
import java.util.Objects;
import javax.persistence.AttributeOverride;
import javax.persistence.AttributeOverrides;
import javax.persistence.Cacheable;
import javax.persistence.Column;
import javax.persistence.Embedded;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Cacheable
@Table(name = "x_resource_mapping_diff")
public class XXResourceMappingDiff implements Serializable {
    private static final long serialVersionUID = 1L;

    @Id
    @Column(name = "id")
    protected Long id;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "name", column = @Column(name = "old_name")),
        @AttributeOverride(name = "location", column = @Column(name = "old_location"))
    })
    protected ResourceMapping oldEntity;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "name", column = @Column(name = "new_name")),
        @AttributeOverride(name = "location", column = @Column(name = "new_location"))
    })
    protected ResourceMapping newEntity;

    @Column(name = "entity_type")
    protected String entityType;

    @Column(name = "diff_type")
    protected String diffType;

    @Column(name = "source_service")
    protected String sourceService;

    @Column(name = "target_service")
    protected String targetService;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public ResourceMapping getOldEntity() {
        return oldEntity;
    }

    public void setOldEntity(ResourceMapping oldEntity) {
        this.oldEntity = oldEntity;
    }

    public ResourceMapping getNewEntity() {
        return newEntity;
    }

    public void setNewEntity(ResourceMapping newEntity) {
        this.newEntity = newEntity;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public String getDiffType() {
        return diffType;
    }

    public void setDiffType(String diffType) {
        this.diffType = diffType;
    }

    public String getSourceService() {
        return sourceService;
    }

    public void setSourceService(String sourceService) {
        this.sourceService = sourceService;
    }

    public String getTargetService() {
        return targetService;
    }

    public void setTargetService(String targetService) {
        this.targetService = targetService;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        XXResourceMappingDiff that = (XXResourceMappingDiff) o;
        return Objects.equals(id, that.id)
            && Objects.equals(oldEntity, that.oldEntity)
            && Objects.equals(newEntity, that.newEntity)
            && Objects.equals(entityType, that.entityType)
            && Objects.equals(diffType, that.diffType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), id, oldEntity, newEntity, entityType, diffType);
    }

    @Override
    public String toString() {
        return "XXMetastoreMappingDiff{" +
            ", diffType='" + diffType + '\'' +
            ", entityType='" + entityType + '\'' +
            ", newEntity=" + newEntity +
            ", oldEntity=" + oldEntity +
            ", id=" + id +
            '}';
    }
}
