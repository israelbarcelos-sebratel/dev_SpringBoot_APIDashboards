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
 * Who a Google-verified e-mail is in the Chrome extension: which atendente name(s) its times come
 * from and its role.
 *
 * <p>The binding is automatic, from the Matrix data ({@link VinculoMatrix}) — nobody picks their own
 * name. An admin can override it for the exceptions (someone who doesn't show up in Matrix, a wrong
 * e-mail there): {@code usuarios_extensao.manual = 1} with an explicit {@code atendente}. The same
 * table holds roles ({@code app-schema.sql}, app database — see {@link DataSourcesConfig}).
 *
 * <p>{@code app.admin-emails} (ADMIN_EMAILS) bootstraps the first admins: those e-mails are always
 * admin regardless of the table, so someone can manage users before any row says "admin".
 */
@Component
public class UsuarioRepository {

    public static final String ADMIN = "admin";
    public static final String USER = "user";

    /** How the atendente names were found: from Matrix, set by an admin, or not found at all. */
    public static final String VINCULO_MATRIX = "matrix";
    public static final String VINCULO_MANUAL = "manual";

    private final JdbcTemplate db;
    private final VinculoMatrix vinculoMatrix;
    private final Set<String> bootstrapAdmins;

    public UsuarioRepository(@Qualifier(DataSourcesConfig.APP) JdbcTemplate db,
                             VinculoMatrix vinculoMatrix,
                             @Value("${app.admin-emails:}") String adminEmails) {
        this.db = db;
        this.vinculoMatrix = vinculoMatrix;
        this.bootstrapAdmins = Arrays.stream(adminEmails.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    /**
     * @param atendente main name (shown in the UI), null when unbound
     * @param nomes     every name whose times count for this user (one person can have several)
     * @param vinculo   {@link #VINCULO_MATRIX}, {@link #VINCULO_MANUAL} or null (unbound)
     * @param preferido the one name this user chose to use when {@code nomes} has several (someone
     *                  who changed areas has one cadastro per area); null = all of them combined
     */
    public record Usuario(String email, String atendente, List<String> nomes, String role, String vinculo,
                          String preferido) {
    }

    private record Linha(String atendente, String role, boolean manual, String preferido) {
    }

    /** Always returns a user: unknown e-mails come back as "user" (or "admin" if bootstrapped). */
    public Usuario find(String email) {
        String chave = email.toLowerCase(Locale.ROOT);
        Optional<Linha> row = db.query(
                "SELECT atendente, role, manual, preferido FROM usuarios_extensao WHERE email = ?",
                (rs, i) -> new Linha(rs.getString("atendente"), rs.getString("role"), rs.getBoolean("manual"),
                        rs.getString("preferido")),
                chave).stream().findFirst();

        List<String> nomes;
        String vinculo;
        if (row.isPresent() && row.get().manual() && row.get().atendente() != null) {
            nomes = List.of(row.get().atendente());
            vinculo = VINCULO_MANUAL;
        } else {
            nomes = vinculoMatrix.nomes(chave);
            vinculo = nomes.isEmpty() ? null : VINCULO_MATRIX;
        }
        String role = bootstrapAdmins.contains(chave) ? ADMIN : row.map(Linha::role).orElse(USER);
        // A escolha só vale enquanto o nome ainda estiver entre os cadastros do e-mail.
        String preferido = row.map(Linha::preferido).filter(nomes::contains).orElse(null);
        String principal = preferido != null ? preferido : nomes.isEmpty() ? null : nomes.get(0);
        return new Usuario(chave, principal, nomes, role, vinculo, preferido);
    }

    /**
     * The user's own choice among their cadastros (null = all combined), kept so they don't have to
     * pick again at every login. Only touches {@code preferido}: role and binding stay as they are.
     */
    public void salvarPreferido(String email, String preferido) {
        db.update("""
                INSERT INTO usuarios_extensao (email, role, manual, preferido, atualizado_em) VALUES (?, ?, 0, ?, NOW())
                ON DUPLICATE KEY UPDATE preferido = VALUES(preferido), atualizado_em = NOW()
                """, email.toLowerCase(Locale.ROOT), USER, preferido);
    }

    /** Everyone with a row (a role or a manual binding), resolved like {@link #find}. */
    public List<Usuario> listAll() {
        return db.queryForList("SELECT email FROM usuarios_extensao ORDER BY email", String.class)
                .stream().map(this::find).toList();
    }

    /**
     * Admin-only. {@code atendente} null keeps (or returns to) the automatic Matrix binding and only
     * sets the role; a name makes it a manual override.
     */
    public void upsert(String email, String atendente, String role) {
        db.update("""
                INSERT INTO usuarios_extensao (email, atendente, role, manual, atualizado_em) VALUES (?, ?, ?, ?, NOW())
                ON DUPLICATE KEY UPDATE atendente = VALUES(atendente), role = VALUES(role),
                                        manual = VALUES(manual), atualizado_em = NOW()
                """, email.toLowerCase(Locale.ROOT), atendente, role, atendente != null);
    }
}
