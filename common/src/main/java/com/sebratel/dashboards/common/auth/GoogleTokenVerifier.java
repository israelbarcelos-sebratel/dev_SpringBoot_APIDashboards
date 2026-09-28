package com.sebratel.dashboards.common.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates the Google OAuth access token the Chrome extension obtains via chrome.identity, using
 * Google's tokeninfo endpoint. A token is only accepted if it was issued to OUR OAuth client (aud),
 * belongs to a verified e-mail and that e-mail is in the allowed company domain.
 *
 * <p>Results are cached until the token expires (capped at 5 min): the extension polls every 15s per
 * API, and asking Google on every request would add latency and hit tokeninfo's rate limits.
 */
@Component
public class GoogleTokenVerifier {

    private static final long MAX_CACHE_SECONDS = 300;

    private final RestClient http = RestClient.create("https://oauth2.googleapis.com");
    private final String clientId;
    private final String allowedDomain;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public GoogleTokenVerifier(@Value("${app.google-client-id}") String clientId,
                               @Value("${app.allowed-email-domain}") String allowedDomain) {
        this.clientId = clientId;
        this.allowedDomain = allowedDomain.toLowerCase(Locale.ROOT);
    }

    /** The verified, lower-cased e-mail behind {@code accessToken}; throws {@link AuthException} otherwise. */
    public String verify(String accessToken) {
        Cached hit = cache.get(accessToken);
        if (hit != null && hit.until.isAfter(Instant.now())) {
            return hit.email;
        }

        Map<?, ?> info = http.get()
                .uri(u -> u.path("/tokeninfo").queryParam("access_token", accessToken).build())
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, resp) -> {
                    throw new AuthException(401, "Token Google inválido ou expirado.");
                })
                .body(Map.class);

        if (info == null || !clientId.equals(info.get("aud"))) {
            throw new AuthException(401, "Token não foi emitido para esta aplicação.");
        }
        if (!"true".equals(String.valueOf(info.get("email_verified")))) {
            throw new AuthException(401, "E-mail da conta Google não verificado.");
        }
        String email = String.valueOf(info.get("email")).toLowerCase(Locale.ROOT);
        if (!email.endsWith("@" + allowedDomain)) {
            throw new AuthException(403, "Apenas contas @" + allowedDomain + " podem acessar.");
        }

        long expiresIn = Long.parseLong(String.valueOf(info.get("expires_in")));
        cache.put(accessToken, new Cached(email, Instant.now().plusSeconds(Math.min(expiresIn, MAX_CACHE_SECONDS))));
        if (cache.size() > 10_000) {
            cache.entrySet().removeIf(e -> e.getValue().until.isBefore(Instant.now()));
        }
        return email;
    }

    private record Cached(String email, Instant until) {
    }
}
