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

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.HttpClient;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Optional;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class AdsccRestServiceTest {
    private final HttpClient httpClient = mock(HttpClient.class);
    private final AdsccRestService adsccRestService = new AdsccRestService(httpClient);

    @Test
    public void execute() throws Exception {
        HttpResponse httpResponse = mock(HttpResponse.class);
        HttpEntity httpEntity = mock(HttpEntity.class);
        String text = "text";
        JsonElement jsonElement = new JsonPrimitive(text);
        when(httpEntity.getContent()).thenReturn(new ByteArrayInputStream(jsonElement.toString().getBytes()));
        when(httpResponse.getEntity()).thenReturn(httpEntity);
        when(httpClient.execute(any())).thenReturn(httpResponse);
        String url = "url";
        String username = "username";
        String password = "password";
        Optional<JsonElement> result = adsccRestService.execute(url, username, password);
        verify(httpClient).execute(argThat(request -> {
            assertEquals(url, request.getURI().toString());
            assertEquals("Accept: application/json", request.getFirstHeader(HttpHeaders.ACCEPT).toString());
            assertEquals("Authorization: Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes()), request.getFirstHeader(HttpHeaders.AUTHORIZATION).toString());
            return true;
        }));
        if (result.isPresent()) {
            assertEquals(text, result.get().getAsString());
        } else {
            fail();
        }
    }

    @Test
    public void getStatusCode() throws IOException {
        HttpResponse httpResponse = mock(HttpResponse.class);
        StatusLine statusLine = mock(StatusLine.class);
        when(statusLine.getStatusCode()).thenReturn(200);
        when(httpResponse.getStatusLine()).thenReturn(statusLine);
        when(httpClient.execute(any())).thenReturn(httpResponse);
        String url = "url";
        String username = "username";
        String password = "password";
        assertEquals(statusLine.getStatusCode(), adsccRestService.getStatusCode(url, username, password));
        verify(httpClient).execute(argThat(request -> {
            assertEquals(url, request.getURI().toString());
            assertEquals("Accept: application/json", request.getFirstHeader(HttpHeaders.ACCEPT).toString());
            assertEquals("Authorization: Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes()), request.getFirstHeader(HttpHeaders.AUTHORIZATION).toString());
            return true;
        }));
    }
}