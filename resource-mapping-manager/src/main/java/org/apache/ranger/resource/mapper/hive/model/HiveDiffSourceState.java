package org.apache.ranger.resource.mapper.hive.model;

public enum HiveDiffSourceState {
    SNAPSHOT_STARTED,
    SNAPSHOT_FINISHED,
    INTERMEDIATE_EVENTS_STARTED,
    INTERMEDIATE_EVENTS_FINISHED,
    EVENTS_STARTED
}
