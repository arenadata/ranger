package org.apache.ranger.services.adscc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
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