package org.apache.ranger.services.ssm;

import org.apache.ranger.plugin.service.RangerBaseService;
import org.apache.ranger.plugin.service.ResourceLookupContext;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RangerServiceSsm extends RangerBaseService {

    @Override
    public Map<String, Object> validateConfig() throws Exception {
        Map<String, Object> result = new HashMap<>();
        result.put("connectivityStatus", true);
        return result;
    }

    @Override
    public List<String> lookupResource(ResourceLookupContext context) throws Exception {
        return Collections.emptyList();
    }
}
