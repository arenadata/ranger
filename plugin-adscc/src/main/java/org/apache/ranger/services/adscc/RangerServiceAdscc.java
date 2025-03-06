package org.apache.ranger.services.adscc;

import org.apache.http.client.HttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.ranger.plugin.model.RangerService;
import org.apache.ranger.plugin.model.RangerServiceDef;
import org.apache.ranger.plugin.service.RangerBaseService;
import org.apache.ranger.plugin.service.ResourceLookupContext;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RangerServiceAdscc extends RangerBaseService {
    private static final Logger logger = LoggerFactory.getLogger(RangerServiceAdscc.class);
    private static final String HOST_CONFIG = "hostname";
    private static final String USERNAME_CONFIG = "username";
    private static final String PASSWORD_CONFIG = "password";
    private static final String SSL_TRUSTSTORE_CONFIG = "ssl_truststore";
    private static final String SSL_TRUSTSTORE_TYPE_CONFIG = "ssl_truststore_type";
    private static final String SSL_TRUSTSTORE_PASSWORD_CONFIG = "ssl_truststore_password";


    private AdsccRestService adsccRestService;
    private RangerAdsccLookupService rangerAdsccLookupService;

    public RangerServiceAdscc() {
        super();
    }

    @Override
    public void init(final RangerServiceDef serviceDef, final RangerService service) {
        super.init(serviceDef, service);
        adsccRestService = new AdsccRestService(getHttpClient());
        rangerAdsccLookupService = new RangerAdsccLookupService(adsccRestService);
    }

    @Override
    public Map<String, Object> validateConfig() throws Exception {
        Map<String, Object> result = new HashMap<>();
        try {
            String hostname = configs.get(HOST_CONFIG);
            if (hostname.lastIndexOf("/") == hostname.length() - 1) {
                result.put(HOST_CONFIG, "Incorrect format, remove last character /");
            } else {
                int statusCode = AdsccEntityEnum.CLUSTER.getUrl(new HashMap<>())
                        .map(url -> adsccRestService.getStatusCode(
                                configs.get(HOST_CONFIG) + url,
                                configs.get(USERNAME_CONFIG),
                                configs.get(PASSWORD_CONFIG)))
                        .orElse(404);
                if (statusCode == 400) {
                    result.put("connectivityStatus", false);
                    result.put("message", "Wrong hostname");
                } else if (statusCode == 401) {
                    result.put("connectivityStatus", false);
                    result.put("message", "Wrong username or password");
                } else {
                    result.put("connectivityStatus", true);
                }
            }
        } catch (Exception e) {
            result.put("connectivityStatus", false);
            result.put("message", "Wrong hostname");
        }
        return result;
    }

    @Override
    public List<String> lookupResource(final ResourceLookupContext context) throws Exception {
        try {
            return rangerAdsccLookupService.lookup(AdsccEntityEnum.valueOf(context.getResourceName().toUpperCase()),
                    context.getResources(),
                    context.getUserInput(),
                    configs.get(HOST_CONFIG),
                    configs.get(USERNAME_CONFIG),
                    configs.get(PASSWORD_CONFIG));
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private HttpClient getHttpClient() {
        HttpClientBuilder httpClientBuilder = HttpClientBuilder.create();
        if (configs.get(HOST_CONFIG).startsWith("https")) {
            String trustStore = configs.get(SSL_TRUSTSTORE_CONFIG);
            String trustStoreType = configs.get(SSL_TRUSTSTORE_TYPE_CONFIG);
            String trustStorePassword = configs.get(SSL_TRUSTSTORE_PASSWORD_CONFIG);

            validateNotBlank(trustStore, "Keystore is required for " + serviceName + " with Authentication Type of SSL");
            validateNotBlank(trustStoreType, "Keystore Type is required for " + serviceName + " with Authentication Type of SSL");
            validateNotBlank(trustStorePassword, "Keystore Password is required for " + serviceName + " with Authentication Type of SSL");

            logger.debug("Creating SSLContext for ADSCC connection");
            try {
                httpClientBuilder.setSSLContext(createSslContext(trustStore, trustStorePassword, trustStoreType));
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        }
        return httpClientBuilder.build();
    }

    private SSLContext createSslContext(String trustStore, String trustStorePassword, String trustStoreType) throws Exception {
        KeyStore trustStoreInstance = KeyStore.getInstance(trustStoreType);
        try (InputStream trustStoreStream = Files.newInputStream(Paths.get(trustStore))) {
            trustStoreInstance.load(trustStoreStream, trustStorePassword.toCharArray());
        }
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStoreInstance);
        SSLContext sslContext = SSLContext.getInstance("SSL");
        sslContext.init(null, trustManagerFactory.getTrustManagers(), new SecureRandom());
        return sslContext;
    }

    private void validateNotBlank(String input, String message) {
        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException(message);
        }
    }
}
