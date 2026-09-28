package com.sebratel.dashboards.common.auth;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * {@code GET /auth/role?atendente=X} — tells the TMA/TME Chrome extension whether the atendente the
 * user declared themselves to be is on the server-side admin allowlist ({@link AdminsProperties}).
 * A common user's role always comes back "user", even if their local extension storage claims
 * otherwise, since the allowlist — not the browser — is the source of truth.
 *
 * <p>Note this is name-based, not session-based: nothing stops a user from typing someone else's
 * name in the extension popup. It raises the bar from "anyone can flip a local toggle" to "you need
 * to know/impersonate an admin's exact registered name", which matches every other endpoint in this
 * app (no login exists here at all) — a real fix would need actual authentication in front of it.
 */
@RestController
public class RoleController {

    private final AdminsProperties admins;

    public RoleController(AdminsProperties admins) {
        this.admins = admins;
    }

    @GetMapping("/auth/role")
    public Map<String, String> role(@RequestParam String atendente) {
        return Map.of(
                "atendente", atendente,
                "role", admins.isAdmin(atendente) ? "admin" : "user");
    }
}
