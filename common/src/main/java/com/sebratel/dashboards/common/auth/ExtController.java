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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * API of the TMA/TME Chrome extension. Every route is behind {@link ExtAuthInterceptor}, so the
 * caller's identity is a Google-verified company e-mail — never a name typed in the browser. The
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

    public ExtController(UsuarioRepository usuarios, SemanticService semantic,
                         TemposAtendenteCache temposCache, TableGroupProperties groupProperties,
                         TemposHojeJob temposHoje) {
        this.usuarios = usuarios;
        this.semantic = semantic;
        this.temposCache = temposCache;
        this.groupProperties = groupProperties;
        this.temposHoje = temposHoje;
    }

    @GetMapping("/ext/me")
    public Usuario me(@RequestAttribute(ExtAuthInterceptor.EMAIL_ATTR) String email) {
        return usuarios.find(email);
    }

    /** First login: the user picks their own atendente once. Changing it later is admin-only. */
    @PostMapping("/ext/me/atendente")
    public Usuario vincular(@RequestAttribute(ExtAuthInterceptor.EMAIL_ATTR) String email,
                            @RequestBody Map<String, String> body) {
        String atendente = requireAtendente(body.get("atendente"));
        if (!usuarios.bindIfAbsent(email, atendente)) {
            throw new AuthException(409, "Seu usuário já está vinculado. Peça a um administrador para alterar.");
        }
        return usuarios.find(email);
    }

    @GetMapping("/ext/atendentes")
    public List<String> atendentes() {
        return nomesAtendentes();
    }

    /**
     * Times for {@code atendente} (defaults to the caller's own). Common users asking for someone else
     * get 403 — the extension can't bypass this by editing its storage.
     */
    @GetMapping("/ext/tempos")
    public Map<String, Object> tempos(@RequestAttribute(ExtAuthInterceptor.EMAIL_ATTR) String email,
                                      @RequestParam(required = false) String atendente,
                                      @RequestParam(required = false) Integer meses) {
        Usuario u = usuarios.find(email);
        String alvo = resolverAlvo(u, atendente);
        Map<String, String> filtros = new HashMap<>();
        filtros.put("atendente", alvo);
        MetricResponse resp = semantic.tempos(DOMINIO, filtros, meses == null ? 1 : meses);
        Object categorias = resp.dados() instanceof Map<?, ?> m ? m.get("categorias") : List.of();
        return Map.of("atendente", alvo, "role", u.role(), "categorias", categorias);
    }

    /**
     * Everything the floating widget shows, in one call: today's averages (in-memory, refreshed every
     * minute by {@link TemposHojeJob}), the 6-week averages from {@code agg_tempos_atendente} for
     * context, and how fresh the ingested data is ({@code ultimoRegistro}). Metric keys are this
     * app's "tempos" names (native: atendimento/espera; matrix: tempoFila/tmic/tmia). Same visibility
     * rule as /ext/tempos.
     */
    @GetMapping("/ext/widget")
    public Map<String, Object> widget(@RequestAttribute(ExtAuthInterceptor.EMAIL_ATTR) String email,
                                      @RequestParam(required = false) String atendente) {
        Usuario u = usuarios.find(email);
        String alvo = resolverAlvo(u, atendente);
        TemposHojeJob.Snapshot hoje = temposHoje.snapshot();

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("sistema", groupProperties.groupName());
        resp.put("atendente", alvo);
        resp.put("role", u.role());
        resp.put("hoje", hoje.porAtendente().getOrDefault(alvo, Map.of()));
        resp.put("seisSemanas", temposCache.get(groupProperties.groupName(), alvo));
        resp.put("ultimoRegistro", hoje.ultimoRegistro());
        resp.put("calculadoEm", hoje.calculadoEm());
        return resp;
    }

    /** Target atendente for a read: the caller's own by default; someone else's only for admins. */
    private String resolverAlvo(Usuario u, String atendente) {
        if (u.atendente() == null) {
            throw new AuthException(409, "Vincule seu usuário a um atendente no popup da extensão.");
        }
        String alvo = (atendente == null || atendente.isBlank()) ? u.atendente() : atendente;
        if (!alvo.equals(u.atendente()) && !UsuarioRepository.ADMIN.equals(u.role())) {
            throw new AuthException(403, "Somente administradores podem ver dados de outros atendentes.");
        }
        return alvo;
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
        usuarios.upsert(alvo, requireAtendente(body.get("atendente")), role);
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
     * the rest into "(outros)", which would leave part of the team unable to bind. Falls back to that
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
