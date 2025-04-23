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
