package org.apache.ranger.services.adscc;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.ranger.plugin.util.PasswordUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

public class AdsccRestService {
    private static final Logger logger =  LoggerFactory.getLogger(AdsccRestService.class);
    private final HttpClient httpClient;

    public AdsccRestService(final HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public Optional<JsonElement> execute(final String url,
                                         final String username,
                                         final String password) {
        try {
            HttpResponse response = getResponse(url, username, password);
            InputStream responseContent = response.getEntity().getContent();
            String responseContentString = IOUtils.toString(responseContent, StandardCharsets.UTF_8);
            JsonElement responseContentJson = JsonParser.parseString(responseContentString);
            return Optional.ofNullable(responseContentJson);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return Optional.empty();
        }
    }

    public int getStatusCode(final String url,
                             final String username,
                             final String password) {
        try {
            return getResponse(url, username, password).getStatusLine().getStatusCode();
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return 404;
        }
    }


    private HttpResponse getResponse(final String url,
                                     final String username,
                                     final String password) throws IOException {
        HttpUriRequest request = new HttpGet(url);
        request.addHeader(HttpHeaders.ACCEPT, "application/json");
        String pass = Optional.ofNullable(password).map(this::getPassword).orElse("");
        Optional.ofNullable(username)
                .map(value -> "Basic " + Base64.getEncoder().encodeToString((username + ":" + pass).getBytes()))
                .ifPresent(auth -> request.addHeader(HttpHeaders.AUTHORIZATION, auth));
        return httpClient.execute(request);
    }

    private String getPassword(final String password) {
        try {
            return PasswordUtils.decryptPassword(password);
        } catch (IOException e) {
            return password;
        }
    }
}
