package org.apache.ranger.resource.mapper.hive.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.ranger.resource.mapper.model.ResourceDiffStreamRecord;

@RequiredArgsConstructor
@Getter
public class HiveChangeStateStreamRecord implements ResourceDiffStreamRecord {
    private final HiveDiffSourceState newState;

    public static ResourceDiffStreamRecord stateChangeRecord(HiveDiffSourceState state) {
        return new HiveChangeStateStreamRecord(state);
    }
}
