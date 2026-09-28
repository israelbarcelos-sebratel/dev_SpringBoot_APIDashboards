package com.sebratel.dashboards.common.auth;

import com.sebratel.dashboards.common.auth.UsuarioRepository.Usuario;
import com.sebratel.dashboards.common.cache.TemposAtendenteCache;
import com.sebratel.dashboards.common.cache.TemposHojeJob;
import com.sebratel.dashboards.common.config.TableGroupProperties;
import com.sebratel.dashboards.common.semantic.MetricResponse;
import com.sebratel.dashboards.common.semantic.SemanticService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * API of the TMA/TME Chrome extension. Every route is behind {@link ExtAuthInterceptor}, so the
 * caller's identity is a Google-verified company e-mail — never a name typed in the browser — and
 * its atendente comes from the Matrix data ({@link VinculoMatrix}), not from the user's choice. The
 * visibility rule lives here, server-side: a common user only ever gets their own atendente's
 * times; an admin may ask for anyone's.
 */
@RestController
public class ExtController {

    private static final String DOMINIO = "atendimentos";

    private final UsuarioRepository usuarios;
    private final SemanticService semantic;
    private final TemposAtendenteCache temposCache;
    private final TableGroupProperties groupProperties;
    private final TemposHojeJob temposHoje;
    private final SuporteService suporte;

    public ExtController(UsuarioRepository usuarios, SemanticService semantic,
                         TemposAtendenteCache temposCache, TableGroupProperties groupProperties,
                         TemposHojeJob temposHoje, SuporteService suporte) {
        this.usuarios = usuarios;
        this.semantic = semantic;
        this.temposCache = temposCache;
        this.groupProperties = groupProperties;
        this.temposHoje = temposHoje;
        this.suporte = suporte;
    }

    @GetMapping("/ext/me")
    public Usuario me(@RequestAttribute(ExtAuthInterceptor.EMAIL_ATTR) String email) {
        return usuarios.find(email);
    }

    /**
     * "Fale com seu administrador": e-mails the development team on the caller's behalf (wrong or
     * missing binding, access to someone's data…). Body: {@code {mensagem}}.
     */
    @PostMapping("/ext/suporte")
    public Map<String, String> suporte(@RequestAttribute(ExtAuthInterceptor.EMAIL_ATTR) String email,
                                       @RequestBody(required = false) Map<String, String> body) {
        suporte.pedirAjuste(usuarios.find(email), body == null ? null : body.get("mensagem"));
        return Map.of("status", "enviado");
    }

    @GetMapping("/ext/atendentes")
    public List<String> atendentes() {
        return nomesAtendentes();
    }

    /**
     * Everything the floating widget shows, in one call: today's averages only (in memory, see
     * {@link TemposHojeJob}) — monthly/longer views are for managers via the dashboards — plus how
     * fresh the ingested data is ({@code ultimoRegistro}). Metric keys come from
     * {@code app.widget.tempos} ("tma"/"tme" in both systems, plus "tmic"/"tmia" in matrix). Same
     * visibility rule for everyone: a common user only gets their own atendente (403 otherwise — the
     * extension can't bypass this by editing its storage); an admin may ask for anyone.
     */
    @GetMapping("/ext/widget")
    public Map<String, Object> widget(@RequestAttribute(ExtAuthInterceptor.EMAIL_ATTR) String email,
                                      @RequestParam(required = false) String atendente) {
        Usuario u = usuarios.find(email);
        List<String> nomes = resolverAlvo(u, atendente);
        TemposHojeJob.Snapshot hoje = temposHoje.hoje();

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("sistema", groupProperties.groupName());
        resp.put("atendente", nomes.get(0));
        resp.put("nomes", nomes);
        resp.put("role", u.role());
        resp.put("hoje", temposHoje.somar(hoje, nomes));
        resp.put("ultimoRegistro", hoje.ultimoRegistro());
        resp.put("calculadoEm", hoje.calculadoEm());
        return resp;
    }

    /**
     * Names whose times to show: all of the caller's own by default (one person can have several);
     * one specific atendente only for admins. Unbound callers get 409, which the extension turns into
     * the "Fale com seu administrador" prompt.
     */
    private List<String> resolverAlvo(Usuario u, String atendente) {
        boolean admin = UsuarioRepository.ADMIN.equals(u.role());
        if (atendente != null && !atendente.isBlank() && !u.nomes().contains(atendente)) {
            if (!admin) {
                throw new AuthException(403, "Somente administradores podem ver dados de outros atendentes.");
            }
            return List.of(atendente);
        }
        if (u.nomes().isEmpty()) {
            throw new AuthException(409, "Seu e-mail não está vinculado a nenhum atendente. Fale com seu administrador pelo popup da extensão.");
        }
        return u.nomes();
    }

    @GetMapping("/ext/usuarios")
    public List<Usuario> listar(@RequestAttribute(ExtAuthInterceptor.EMAIL_ATTR) String email) {
        requireAdmin(email);
        return usuarios.listAll();
    }

    @PutMapping("/ext/usuarios")
    public Usuario salvar(@RequestAttribute(ExtAuthInterceptor.EMAIL_ATTR) String email,
                          @RequestBody Map<String, String> body) {
        requireAdmin(email);
        String alvo = Objects.requireNonNullElse(body.get("email"), "").trim().toLowerCase(Locale.ROOT);
        if (alvo.isEmpty()) {
            throw new IllegalArgumentException("Informe o e-mail.");
        }
        String role = UsuarioRepository.ADMIN.equals(body.get("role")) ? UsuarioRepository.ADMIN : UsuarioRepository.USER;
        // Atendente vazio = volta ao vínculo automático pela Matrix (só define o papel).
        String atendente = body.get("atendente");
        usuarios.upsert(alvo, atendente == null || atendente.isBlank() ? null : requireAtendente(atendente), role);
        return usuarios.find(alvo);
    }

    private void requireAdmin(String email) {
        if (!UsuarioRepository.ADMIN.equals(usuarios.find(email).role())) {
            throw new AuthException(403, "Somente administradores.");
        }
    }

    private String requireAtendente(String atendente) {
        if (atendente == null || !nomesAtendentes().contains(atendente)) {
            throw new IllegalArgumentException("Atendente inválido.");
        }
        return atendente;
    }

    /**
     * Full list from the tempos cache: /por/atendente caps its distribution at the top 100 and lumps
     * the rest into "(outros)", which would leave part of the team out of the admin lists. Falls back to that
     * capped list only while the cache is still empty (first minute after deploy).
     */
    @SuppressWarnings("unchecked")
    private List<String> nomesAtendentes() {
        List<String> cached = temposCache.listAtendentes(groupProperties.groupName());
        if (!cached.isEmpty()) {
            return cached;
        }
        MetricResponse resp = semantic.porDimensao(DOMINIO, "atendente", Map.of(), 1);
        if (!(resp.dados() instanceof Map<?, ?> dados)) {
            return List.of();
        }
        return ((List<Map<String, Object>>) dados.get("categorias")).stream()
                .map(c -> String.valueOf(c.get("rotulo")))
                .filter(n -> !n.isBlank() && !"(vazio)".equals(n) && !"(outros)".equals(n))
                .sorted()
                .toList();
    }
}
