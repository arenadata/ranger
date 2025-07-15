package org.apache.ranger.resource.mapper.hive.model;

import lombok.Data;
import org.apache.ranger.resource.mapper.model.ResourceDiffStreamRecord;

@Data
public class NewHiveSourceStateRecord implements ResourceDiffStreamRecord {
    private final HiveDiffSourceState newState;

    public static ResourceDiffStreamRecord newStateRecord(HiveDiffSourceState state) {
        return new NewHiveSourceStateRecord(state);
    }
}
