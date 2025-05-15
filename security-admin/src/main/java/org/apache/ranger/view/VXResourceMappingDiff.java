package org.apache.ranger.view;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.Serializable;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class VXResourceMappingDiff implements Serializable {
    private final VXResourceMapping oldEntity;
    private final VXResourceMapping newEntity;
    private final String entityType;
    private final String diffType;
    private final long id;
    private final String sourceService;
    private final String targetService;

    public VXResourceMappingDiff(
        VXResourceMapping oldEntity,
        VXResourceMapping newEntity,
        String entityType,
        String diffType,
        long id,
        String sourceService,
        String targetService
    ) {
        this.oldEntity = oldEntity;
        this.newEntity = newEntity;
        this.entityType = entityType;
        this.diffType = diffType;
        this.id = id;
        this.sourceService = sourceService;
        this.targetService = targetService;
    }

    public VXResourceMapping getOldEntity() {
        return oldEntity;
    }

    public VXResourceMapping getNewEntity() {
        return newEntity;
    }

    public String getEntityType() {
        return entityType;
    }

    public String getDiffType() {
        return diffType;
    }

    public String getSourceService() {
        return sourceService;
    }

    public String getTargetService() {
        return targetService;
    }

    public long getId() {
        return id;
    }
}
