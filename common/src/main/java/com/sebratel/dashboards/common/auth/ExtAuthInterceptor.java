package com.sebratel.dashboards.common.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Guards every {@code /ext/**} route (the Chrome extension's API): requires
 * {@code Authorization: Bearer <Google access token>} and exposes the verified e-mail as the
 * {@link #EMAIL_ATTR} request attribute. The pre-existing open routes used by the dashboards are
 * untouched.
 */
@Component
public class ExtAuthInterceptor implements HandlerInterceptor {

    public static final String EMAIL_ATTR = "extEmail";

    private final GoogleTokenVerifier verifier;

    public ExtAuthInterceptor(GoogleTokenVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true; // CORS preflight carries no Authorization header
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new AuthException(401, "Login necessário.");
        }
        request.setAttribute(EMAIL_ATTR, verifier.verify(header.substring("Bearer ".length()).trim()));
        return true;
    }
}
