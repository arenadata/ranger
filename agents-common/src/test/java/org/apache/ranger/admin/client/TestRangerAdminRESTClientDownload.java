/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.ranger.admin.client;

import com.sun.jersey.api.client.ClientResponse;
import org.apache.ranger.plugin.util.RangerRESTClient;
import org.apache.ranger.plugin.util.RangerServiceNotFoundException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import javax.ws.rs.core.Cookie;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@RunWith(Parameterized.class)
public class TestRangerAdminRESTClientDownload {
    @Parameterized.Parameters(name = "{0}, cookie={1}")
    public static Collection<Object[]> parameters() {
        return Arrays.asList(new Object[][] {
                {"policy", false}, {"policy", true},
                {"role", false}, {"role", true},
                {"tag", false}, {"tag", true}
        });
    }

    private final String endpoint;
    private final boolean cookie;
    private RangerAdminRESTClient client;
    private ClientResponse response;

    public TestRangerAdminRESTClientDownload(String endpoint, boolean cookie) {
        this.endpoint = endpoint;
        this.cookie = cookie;
    }

    @Before
    public void setUp() throws Exception {
        client = new RangerAdminRESTClient();
        RangerRESTClient transport = mock(RangerRESTClient.class);
        response = mock(ClientResponse.class);
        when(transport.get(anyString(), anyMap())).thenReturn(response);
        when(transport.get(anyString(), anyMap(), nullable(Cookie.class))).thenReturn(response);
        when(response.getCookies()).thenReturn(Collections.emptyList());
        when(response.getEntity(String.class)).thenReturn("{}");
        setField("restClient", transport);
        setField("serviceName", "spark_test");
        setField("serviceNameUrlParam", "spark_test");
        setField("isRangerCookieEnabled", cookie);
        if (cookie) {
            setField(endpoint + "DownloadSessionId", new Cookie("RANGERADMINSESSIONID", "test"));
            setField("isValid" + capitalizedEndpoint() + "DownloadSessionCookie", true);
        }
    }

    @Test
    public void unauthorizedIsAnAuthorizationDenial() throws Exception {
        assertDenied(401);
    }

    @Test
    public void forbiddenIsAnAuthorizationDenial() throws Exception {
        assertDenied(403);
    }

    @Test
    public void serverErrorIsTransient() throws Exception {
        when(response.getStatus()).thenReturn(503);
        try {
            download();
            fail("Expected a transient download failure");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("503"));
        }
    }

    @Test
    public void nullResponseIsTransientRatherThanSuccessfulRefresh() throws Exception {
        RangerRESTClient transport = (RangerRESTClient) getField("restClient");
        when(transport.get(anyString(), anyMap())).thenReturn(null);
        when(transport.get(anyString(), anyMap(), nullable(Cookie.class))).thenReturn(null);
        try {
            download();
            fail("Expected a transient download failure");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Null response"));
        }
    }

    @Test
    public void notModifiedIsSuccessfulRefresh() throws Exception {
        when(response.getStatus()).thenReturn(304);
        assertNull(download());
    }

    @Test
    public void successfulDownloadReturnsData() throws Exception {
        when(response.getStatus()).thenReturn(200);
        assertNotNull(download());
    }

    @Test
    public void missingServiceRetainsItsOwnException() throws Exception {
        when(response.getStatus()).thenReturn(404);
        when(response.hasEntity()).thenReturn(true);
        when(response.getEntity(String.class)).thenReturn(RangerServiceNotFoundException.buildExceptionMsg("spark_test"));
        try {
            download();
            fail("Expected service-not-found failure");
        } catch (RangerServiceNotFoundException expected) {
            assertEquals("spark_test", expected.getMessage());
        }
    }

    private void assertDenied(int status) throws Exception {
        when(response.getStatus()).thenReturn(status);
        when(response.getEntity(String.class)).thenReturn("{\"msgDesc\":\"download denied\"}");
        try {
            download();
            fail("Expected authorization denial");
        } catch (RangerAdminClientAccessDeniedException expected) {
            assertEquals(status, expected.getHttpStatus());
            assertEquals("download denied", expected.getMessage());
        }
        assertNull(getField(endpoint + "DownloadSessionId"));
        if (cookie) {
            assertEquals(false, getField("isValid" + capitalizedEndpoint() + "DownloadSessionCookie"));
        }
    }

    private Object download() throws Exception {
        switch (endpoint) {
            case "policy": return client.getServicePoliciesIfUpdated(-1L, 0L);
            case "role": return client.getRolesIfUpdated(-1L, 0L);
            case "tag": return client.getServiceTagsIfUpdated(-1L, 0L);
            default: throw new AssertionError(endpoint);
        }
    }

    private String capitalizedEndpoint() {
        return Character.toUpperCase(endpoint.charAt(0)) + endpoint.substring(1);
    }

    private void setField(String name, Object value) throws Exception {
        Field field = RangerAdminRESTClient.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(client, value);
    }

    private Object getField(String name) throws Exception {
        Field field = RangerAdminRESTClient.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(client);
    }
}
