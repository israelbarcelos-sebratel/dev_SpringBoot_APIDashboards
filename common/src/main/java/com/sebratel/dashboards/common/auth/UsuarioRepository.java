package com.sebratel.dashboards.common.auth;

import com.sebratel.dashboards.common.config.DataSourcesConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The e-mail → atendente → role binding for the Chrome extension ({@code usuarios_extensao}, see
 * {@code app-schema.sql}), stored in the app's own database — see {@link DataSourcesConfig}.
 *
 * <p>{@code app.admin-emails} (ADMIN_EMAILS) bootstraps the first admins: those e-mails are always
 * admin regardless of the table, so someone can manage the bindings before any row says "admin".
 */
@Component
public class UsuarioRepository {

    public static final String ADMIN = "admin";
    public static final String USER = "user";

    private final JdbcTemplate db;
    private final Set<String> bootstrapAdmins;

    public UsuarioRepository(@Qualifier(DataSourcesConfig.APP) JdbcTemplate db,
                             @Value("${app.admin-emails:}") String adminEmails) {
        this.db = db;
        this.bootstrapAdmins = Arrays.stream(adminEmails.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    public record Usuario(String email, String atendente, String role) {
    }

    /** Always returns a user: unknown e-mails come back unbound, as "user" (or "admin" if bootstrapped). */
    public Usuario find(String email) {
        Optional<Usuario> row = db.query(
                "SELECT email, atendente, role FROM usuarios_extensao WHERE email = ?",
                (rs, i) -> new Usuario(rs.getString("email"), rs.getString("atendente"), rs.getString("role")),
                email).stream().findFirst();
        String atendente = row.map(Usuario::atendente).orElse(null);
        String role = bootstrapAdmins.contains(email) ? ADMIN : row.map(Usuario::role).orElse(USER);
        return new Usuario(email, atendente, role);
    }

    public List<Usuario> listAll() {
        return db.query(
                "SELECT email, atendente, role FROM usuarios_extensao ORDER BY email",
                (rs, i) -> {
                    String email = rs.getString("email");
                    String role = bootstrapAdmins.contains(email) ? ADMIN : rs.getString("role");
                    return new Usuario(email, rs.getString("atendente"), role);
                });
    }

    /** First-login self-binding; returns false if this e-mail is already bound (only an admin can change it). */
    public boolean bindIfAbsent(String email, String atendente) {
        int rows = db.update(
                "INSERT IGNORE INTO usuarios_extensao (email, atendente, role, atualizado_em) VALUES (?, ?, 'user', NOW())",
                email, atendente);
        return rows == 1;
    }

    /** Admin-only upsert of any binding/role. */
    public void upsert(String email, String atendente, String role) {
        db.update("""
                INSERT INTO usuarios_extensao (email, atendente, role, atualizado_em) VALUES (?, ?, ?, NOW())
                ON DUPLICATE KEY UPDATE atendente = VALUES(atendente), role = VALUES(role), atualizado_em = NOW()
                """, email, atendente, role);
    }
}
