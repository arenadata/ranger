package org.apache.ranger.services.adscc;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class RangerAdsccLookupService {
    private final AdsccRestService adsccRestService;

    public RangerAdsccLookupService(final AdsccRestService adsccRestService) {
        this.adsccRestService = adsccRestService;
    }

    public List<String> lookup(final AdsccEntityEnum resource,
                               final Map<String, List<String>> resources,
                               final String input,
                               final String host,
                               final String username,
                               final String password) {
        return Optional.ofNullable(host)
                .flatMap(url -> resource.getUrl(resources).map(resUrl -> url + resUrl))
                .flatMap(url -> adsccRestService.execute(url, username, password)
                        .map(resource.getResponseFunc()))
                .map(result -> result.stream()
                        .filter(value -> value.toLowerCase().contains(input.toLowerCase()))
                        .distinct()
                        .collect(Collectors.toList()))
                .orElse(Collections.emptyList());
    }
}
