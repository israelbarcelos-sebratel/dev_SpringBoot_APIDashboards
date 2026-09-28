package com.sebratel.dashboards.common.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The server-side allowlist of atendente names granted the "admin" role — bound from
 * {@code app.admins} (env var {@code ADMIN_USERS}, comma-separated). This is what makes the role
 * check trustworthy: the Chrome extension has no login, so anything decided purely in the browser
 * (a local toggle, a client-stored flag) could be edited by whoever installed it. Keeping the
 * allowlist here means a common user can't grant themselves admin just by editing extension storage.
 */
@Component
public class AdminsProperties {

    private final List<String> admins;

    public AdminsProperties(@Value("${app.admins:}") String csv) {
        this.admins = Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .toList();
    }

    public boolean isAdmin(String atendente) {
        if (atendente == null) {
            return false;
        }
        return admins.contains(atendente.trim().toLowerCase(Locale.ROOT));
    }
}
