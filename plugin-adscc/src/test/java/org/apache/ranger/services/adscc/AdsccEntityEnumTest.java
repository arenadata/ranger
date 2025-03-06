package org.apache.ranger.services.adscc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AdsccEntityEnumTest {

    @Test
    public void cluster() {
        String result = "result";
        JsonArray jsonArray = new JsonArray();
        JsonObject jsonObject = new JsonObject();
        jsonObject.add("name", new JsonPrimitive(result));
        jsonArray.add(jsonObject);
        check(AdsccEntityEnum.CLUSTER, "/api/v1/clusters", new HashMap<>(), jsonArray, result);
    }

    @Test
    public void broker() {
        String result = "result";
        JsonObject jsonElement = new JsonObject();
        JsonArray jsonArray = new JsonArray();
        JsonObject jsonObject = new JsonObject();
        jsonObject.add("id", new JsonPrimitive(result));
        jsonArray.add(jsonObject);
        jsonElement.add("brokers", jsonArray);
        check(AdsccEntityEnum.BROKER,
                "/api/v1/cluster/test_cluster/brokers",
                Collections.singletonMap(AdsccEntityEnum.CLUSTER.name().toLowerCase(), Collections.singletonList("test_cluster")),
                jsonElement,
                result);
    }

    @Test
    public void topic() {
        String result = "result";
        JsonObject jsonElement = new JsonObject();
        JsonArray jsonArray = new JsonArray();
        JsonObject jsonObject = new JsonObject();
        jsonObject.add("name", new JsonPrimitive(result));
        jsonArray.add(jsonObject);
        jsonElement.add("topics", jsonArray);
        check(AdsccEntityEnum.TOPIC,
                "/api/v1/cluster/test_cluster/topics",
                Collections.singletonMap(AdsccEntityEnum.CLUSTER.name().toLowerCase(), Collections.singletonList("test_cluster")),
                jsonElement,
                result);
    }

    @Test
    public void consumerGroup() {
        String result = "result";
        JsonObject jsonElement = new JsonObject();
        JsonArray jsonArray = new JsonArray();
        JsonObject jsonObject = new JsonObject();
        jsonObject.add("id", new JsonPrimitive(result));
        jsonArray.add(jsonObject);
        jsonElement.add("consumerGroups", jsonArray);
        check(AdsccEntityEnum.CONSUMER_GROUP,
                "/api/v1/cluster/test_cluster/consumerGroups",
                Collections.singletonMap(AdsccEntityEnum.CLUSTER.name().toLowerCase(), Collections.singletonList("test_cluster")),
                jsonElement,
                result);
    }

    @Test
    public void connect() {
        String result = "result";
        JsonArray jsonArray = new JsonArray();
        JsonObject jsonObject = new JsonObject();
        jsonObject.add("name", new JsonPrimitive(result));
        jsonArray.add(jsonObject);
        check(AdsccEntityEnum.CONNECT,
                "/api/v1/cluster/test_cluster/connectClusters",
                Collections.singletonMap(AdsccEntityEnum.CLUSTER.name().toLowerCase(), Collections.singletonList("test_cluster")),
                jsonArray,
                result);
    }

    private void check(final AdsccEntityEnum resource,
                       final String url,
                       final Map<String, List<String>> parentResources,
                       final JsonElement jsonElement,
                       final String result) {
        assertEquals(url, resource.getUrl(parentResources).get());
        Set<String> funcResult = resource.getResponseFunc().apply(jsonElement);
        assertEquals(1, funcResult.size());
        assertTrue(funcResult.contains(result));
    }
}