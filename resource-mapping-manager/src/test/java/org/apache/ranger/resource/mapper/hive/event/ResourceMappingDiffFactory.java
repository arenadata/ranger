package org.apache.ranger.resource.mapper.hive.event;

import static org.apache.ranger.resource.mapper.hive.event.MetastoreEntityDiffFactory.DEFAULT_LOCATION_SCHEME;
import static org.apache.ranger.resource.mapper.hive.event.MetastoreEntityDiffFactory.HIVE_SERVICE;
import static org.apache.ranger.resource.mapper.hive.model.HiveEntityDiffType.UPDATE;

import org.apache.ranger.resource.mapper.hive.model.HiveEntityDiffType;
import org.apache.ranger.resource.mapper.hive.model.HiveEntityType;
import org.apache.ranger.resource.mapper.model.ResourceMapping;
import org.apache.ranger.resource.mapper.model.ResourceMappingDiff;

public class ResourceMappingDiffFactory {
    public static ResourceMappingDiff diff(
        long id,
        HiveEntityType entityType,
        HiveEntityDiffType diffType,
        String name,
        String location
    ) {
        return diffBuilder(id, entityType, diffType)
            .oldEntity(new ResourceMapping(name, location))
            .build();
    }

    public static ResourceMappingDiff updateDiff(
        long id,
        HiveEntityType entityType,
        ResourceMapping oldMapping,
        ResourceMapping newMapping
    ) {
        return diffBuilder(id, entityType, UPDATE)
            .oldEntity(oldMapping)
            .newEntity(newMapping)
            .build();
    }

    public static ResourceMappingDiff.ResourceMappingDiffBuilder diffBuilder(
        long id, HiveEntityType entityType, HiveEntityDiffType diffType) {
        return ResourceMappingDiff.builder()
            .id(id)
            .sourceService(HIVE_SERVICE)
            .targetService(DEFAULT_LOCATION_SCHEME)
            .entityType(entityType.toString())
            .diffType(diffType.toString());
    }

}
