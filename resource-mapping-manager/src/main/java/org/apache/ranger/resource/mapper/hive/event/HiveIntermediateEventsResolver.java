package org.apache.ranger.resource.mapper.hive.event;

import org.apache.ranger.resource.mapper.model.ResourceDiffStreamRecord;

public interface HiveIntermediateEventsResolver {
    void handle(ResourceDiffStreamRecord record);

    void flush();
}
