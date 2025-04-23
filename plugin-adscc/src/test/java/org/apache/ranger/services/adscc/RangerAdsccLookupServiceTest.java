/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ranger.services.adscc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;


import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class RangerAdsccLookupServiceTest {
    private final AdsccRestService adsccRestService = mock(AdsccRestService.class);
    private final RangerAdsccLookupService service = new RangerAdsccLookupService(adsccRestService);

    @Test
    public void lookup() {
        AdsccEntityEnum resource = AdsccEntityEnum.CLUSTER;
        Map<String, List<String>> resources = new HashMap<>();
        String input = "input";
        String host = "host";
        String username = "username";
        String password = "password";
        JsonArray jsonArray = new JsonArray();
        JsonObject jsonObject = new JsonObject();
        String clusterName = "inputClusterName";
        jsonObject.add("name", new JsonPrimitive(clusterName));
        jsonArray.add(jsonObject);
        when(adsccRestService.execute(anyString(), anyString(), anyString())).thenReturn(Optional.of(jsonArray));
        List<String> result = service.lookup(resource, resources, input, host, username, password);
        assertEquals(1, result.size());
        assertEquals(clusterName, result.get(0));
        verify(adsccRestService).execute(resource.getUrl(new HashMap<>()).map(host::concat).orElse(""), username, password);
    }

    @Test
    public void lookupFiltered() {
        AdsccEntityEnum resource = AdsccEntityEnum.CLUSTER;
        Map<String, List<String>> resources = new HashMap<>();
        String input = "input";
        String host = "host";
        String username = "username";
        String password = "password";
        JsonArray jsonArray = new JsonArray();
        JsonObject jsonObject = new JsonObject();
        String clusterName = "clusterName";
        jsonObject.add("name", new JsonPrimitive(clusterName));
        jsonArray.add(jsonObject);
        when(adsccRestService.execute(anyString(), anyString(), anyString())).thenReturn(Optional.of(jsonArray));
        List<String> result = service.lookup(resource, resources, input, host, username, password);
        assertEquals(0, result.size());
        verify(adsccRestService).execute(resource.getUrl(new HashMap<>()).map(host::concat).orElse(""), username, password);
    }
}