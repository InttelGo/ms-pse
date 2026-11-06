package com.inttelgo.mspse.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** RestClient con tiempo máximo de conexión y de respuesta. */
public final class HttpClients {

    private HttpClients() {
    }

    public static RestClient build(RestClient.Builder builder, String baseUrl, Duration timeout) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        RestClient.Builder configured = builder.clone().requestFactory(factory);
        if (baseUrl != null && !baseUrl.isBlank()) {
            configured.baseUrl(trimSlash(baseUrl));
        }
        return configured.build();
    }

    public static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
