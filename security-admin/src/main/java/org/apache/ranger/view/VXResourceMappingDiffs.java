package org.apache.ranger.view;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.Serializable;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class VXResourceMappingDiffs implements Serializable {
    private final List<VXResourceMappingDiff> diffs;

    public VXResourceMappingDiffs(List<VXResourceMappingDiff> diffs) {
        this.diffs = diffs;
    }

    public List<VXResourceMappingDiff> getDiffs() {
        return diffs;
    }
}
