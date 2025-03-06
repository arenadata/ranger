package org.apache.ranger.services.adscc;

import com.google.gson.JsonElement;

import java.util.*;
import java.util.function.Function;

public enum AdsccEntityEnum {
    CLUSTER(resources -> Optional.of("/api/v1/clusters"), jsonElement -> iterate(jsonElement, "name")),
    BROKER(resources -> getParentResourceValue(resources.get(CLUSTER.name().toLowerCase())).map(cluster -> "/api/v1/cluster/" + cluster + "/brokers"),
            jsonElement -> iterate(jsonElement.getAsJsonObject().getAsJsonArray("brokers"), "id")),
    TOPIC(resources -> getParentResourceValue(resources.get(CLUSTER.name().toLowerCase())).map(cluster -> "/api/v1/cluster/" + cluster + "/topics"),
            jsonElement -> iterate(jsonElement.getAsJsonObject().getAsJsonArray("topics"), "name")),
    CONSUMER_GROUP(resources -> getParentResourceValue(resources.get(CLUSTER.name().toLowerCase())).map(cluster -> "/api/v1/cluster/" + cluster + "/consumerGroups"),
            jsonElement -> iterate(jsonElement.getAsJsonObject().getAsJsonArray("consumerGroups"), "id")),
    CONNECT(resources -> getParentResourceValue(resources.get(CLUSTER.name().toLowerCase())).map(cluster -> "/api/v1/cluster/" + cluster + "/connectClusters"),
            jsonElement -> iterate(jsonElement, "name"));


    private final Function<Map<String, List<String>>, Optional<String>> urlFunc;
    private final Function<JsonElement, Set<String>> responseFunc;

    AdsccEntityEnum(final Function<Map<String, List<String>>, Optional<String>> urlFunc, final Function<JsonElement, Set<String>> responseFunc) {
        this.urlFunc = urlFunc;
        this.responseFunc = responseFunc;
    }

    public Optional<String> getUrl(Map<String, List<String>> resources) {
        return urlFunc.apply(resources);
    }

    public Function<JsonElement, Set<String>> getResponseFunc() {
        return responseFunc;
    }

    private static Set<String> iterate(final JsonElement jsonElement, final String fieldName) {
        Iterator<JsonElement> iterator = jsonElement.getAsJsonArray().iterator();
        Set<String> result = new HashSet<>();
        while (iterator.hasNext()) {
            result.add(iterator.next().getAsJsonObject().get(fieldName).getAsString());
        }
        return result;
    }

    private static Optional<String> getParentResourceValue(final List<String> values) {
        return Optional.ofNullable(values)
                .filter(list -> !list.isEmpty())
                .map(list -> list.get(0))
                .map(String::trim)
                .filter(value -> !"*".equals(value));
    }
}
