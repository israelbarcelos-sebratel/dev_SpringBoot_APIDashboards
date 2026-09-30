package com.sebratel.dashboards.common.auth;

import com.sebratel.dashboards.common.auth.UsuarioRepository.Usuario;
import com.sebratel.dashboards.common.cache.ClienteAlias;
import com.sebratel.dashboards.common.cache.ReferenciaMensalJob;
import com.sebratel.dashboards.common.cache.ResumoDiario;
import com.sebratel.dashboards.common.cache.TemposAtendenteCache;
import com.sebratel.dashboards.common.cache.TemposHojeJob;
import com.sebratel.dashboards.common.config.TableGroupProperties;
import com.sebratel.dashboards.common.config.WidgetProperties;
import com.sebratel.dashboards.common.semantic.MetricResponse;
import com.sebratel.dashboards.common.semantic.SemanticService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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
    private final WidgetProperties widgetProperties;
    private final ClienteAlias clienteAlias;
    private final ReferenciaMensalJob referencia;
    private final CorrespondenciaNomes correspondencia;
    private final ResumoDiario resumoDiario;

    public ExtController(UsuarioRepository usuarios, SemanticService semantic,
                         TemposAtendenteCache temposCache, TableGroupProperties groupProperties,
                         TemposHojeJob temposHoje, SuporteService suporte, WidgetProperties widgetProperties,
                         ClienteAlias clienteAlias, ReferenciaMensalJob referencia,
                         CorrespondenciaNomes correspondencia, ResumoDiario resumoDiario) {
        this.usuarios = usuarios;
        this.semantic = semantic;
        this.temposCache = temposCache;
        this.groupProperties = groupProperties;
        this.temposHoje = temposHoje;
        this.suporte = suporte;
        this.widgetProperties = widgetProperties;
        this.clienteAlias = clienteAlias;
        this.referencia = referencia;
        this.correspondencia = correspondencia;
        this.resumoDiario = resumoDiario;
    }

    @GetMapping("/ext/me")
    public Usuario me(@RequestAttribute(ExtAuthInterceptor.EMAIL_ATTR) String email) {
        return usuarios.find(email);
    }

    /**
     * Which of the caller's own cadastros to use (someone who changed areas has one per area). Body
     * {@code {atendente}}: one of {@code /ext/me}'s {@code nomes}, or empty for all combined. Saved in
     * the app database, so the choice survives logout and other computers.
     */
    @PutMapping("/ext/me/preferido")
    public Usuario salvarPreferido(@RequestAttribute(ExtAuthInterceptor.EMAIL_ATTR) String email,
                                   @RequestBody(required = false) Map<String, String> body) {
        Usuario u = usuarios.find(email);
        String escolhido = body == null ? null : body.get("atendente");
        if (escolhido == null || escolhido.isBlank()) {
            escolhido = null;
        } else if (!u.nomes().contains(escolhido)) {
            throw new AuthException(403, "Esse cadastro não está vinculado ao seu e-mail.");
        }
        usuarios.salvarPreferido(email, escolhido);
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
     * {@code app.widget.tempos} ("tma"/"tme" in both systems, plus "tmic"/"tmia" in matrix), plus
     * "tmea" with its sector reference ({@code tmeaReferencia}), each metric's limit ({@code metas})
     * and the number of calls today and in the month so far ({@code atendimentos}). Same
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
        resp.put("metas", metas());
        resp.put("tmeaReferencia", referencia.tmeaSetor(nomes));
        resp.put("atendimentos", atendimentos(hoje, nomes));
        resp.put("ultimoRegistro", hoje.ultimoRegistro());
        resp.put("calculadoEm", hoje.calculadoEm());
        return resp;
    }

    /** metrica -> limit in seconds ({@code app.widget.tempos.*.meta-segundos}), only those that have one. */
    private Map<String, Integer> metas() {
        Map<String, Integer> metas = new LinkedHashMap<>();
        widgetProperties.getTempos().forEach((chave, m) -> {
            if (m.getMetaSegundos() != null) {
                metas.put(chave, m.getMetaSegundos());
            }
        });
        return metas;
    }

    /** {@code {hoje, mes}}: calls today and from the 1st of the month until now (mes null right after midnight). */
    private Map<String, Object> atendimentos(TemposHojeJob.Snapshot hoje, List<String> nomes) {
        long hojeN = temposHoje.atendimentosHoje(hoje, nomes);
        Long ateOntem = referencia.atendimentosMesAteOntem(nomes, hoje.calculadoEm());
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("hoje", hojeN);
        r.put("mes", ateOntem == null ? null : ateOntem + hojeN);
        return r;
    }

    /**
     * The per-call table behind the widget's numbers ("como o meu TMA foi formado"): today's calls of
     * the same atendente(s) {@link #widget} resolves — same visibility rule — with the configured
     * columns and each metric per call, plus every metric's formula and today's average, so the table
     * adds up to what the widget shows.
     */
    @GetMapping("/ext/widget/detalhe")
    public Map<String, Object> detalhe(@RequestAttribute(ExtAuthInterceptor.EMAIL_ATTR) String email,
                                       @RequestParam(required = false) String atendente) {
        Usuario u = usuarios.find(email);
        List<String> nomes = resolverAlvo(u, atendente);
        TemposHojeJob.Snapshot hoje = temposHoje.hoje();
        Map<String, TemposHojeJob.Tempo> medias = temposHoje.somar(hoje, nomes);

        List<Map<String, Object>> metricas = new ArrayList<>();
        widgetProperties.getTempos().forEach((chave, m) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("chave", chave);
            item.put("formula", m.getFormula());
            item.put("meta", m.getMetaSegundos());
            TemposHojeJob.Tempo t = medias.get(chave);
            item.put("segundosMedios", t == null ? null : t.segundosMedios());
            item.put("amostras", t == null ? 0 : t.amostras());
            metricas.add(item);
        });
        WidgetProperties.Tmea tmea = widgetProperties.getTmea();
        if (tmea.configurado()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("chave", TemposHojeJob.TMEA);
            item.put("formula", tmea.getFormula() + " · intervalos acima de " + tmea.getMaxIntervaloMinutos()
                    + " min (pausa, almoço, fim de turno) não entram");
            item.put("meta", null);
            TemposHojeJob.Tempo t = medias.get(TemposHojeJob.TMEA);
            item.put("segundosMedios", t == null ? null : t.segundosMedios());
            item.put("amostras", t == null ? 0 : t.amostras());
            item.put("referencia", referencia.tmeaSetor(nomes));
            metricas.add(item);
        }
        List<Map<String, String>> colunas = widgetProperties.getDetalhe().stream()
                .map(c -> Map.of("chave", c.getChave(), "rotulo", c.getRotulo()))
                .toList();
        List<Map<String, Object>> linhas = temposHoje.detalheHoje(nomes);
        pseudonimizar(linhas);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("sistema", groupProperties.groupName());
        resp.put("atendente", nomes.get(0));
        resp.put("nomes", nomes);
        resp.put("metricas", metricas);
        resp.put("atendimentos", atendimentos(hoje, nomes));
        resp.put("colunas", colunas);
        resp.put("linhas", linhas);
        resp.put("truncado", linhas.size() >= widgetProperties.getDetalheLimite());
        resp.put("ultimoRegistro", hoje.ultimoRegistro());
        return resp;
    }

    /**
     * "Resumo dos últimos dias" of the atendimentos page: per day of the last {@code dias} days with
     * activity, calls, TMA/TME…, time in pause and time logged in this system, plus the person's own
     * averages ({@code voce}) and the sector's ({@code setor}: mean of the colleagues of the same sector,
     * each weighing the same). Same visibility rule as {@link #widget}.
     */
    @GetMapping("/ext/widget/resumo")
    public Map<String, Object> resumo(@RequestAttribute(ExtAuthInterceptor.EMAIL_ATTR) String email,
                                      @RequestParam(required = false) String atendente,
                                      @RequestParam(defaultValue = "7") int dias) {
        Usuario u = usuarios.find(email);
        List<String> nomes = resolverAlvo(u, atendente);
        Map<String, Object> dados = resumoDiario.resumo(nomes, Math.max(1, Math.min(dias, ResumoDiario.MAX_DIAS)),
                referencia::setor);
        if (dados == null) {
            throw new AuthException(503, "O resumo ainda está sendo calculado. Tente em alguns minutos.");
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("sistema", groupProperties.groupName());
        resp.put("atendente", nomes.get(0));
        resp.put("nomes", nomes);
        resp.put("metas", metas());
        resp.putAll(dados);
        return resp;
    }

    /**
     * Swaps customer names ({@code alias: true} columns) for their pseudonyms before anything leaves
     * the server; a name without alias (placeholder like "Não informado") goes out empty.
     */
    private void pseudonimizar(List<Map<String, Object>> linhas) {
        Set<String> chaves = widgetProperties.getDetalhe().stream()
                .filter(WidgetProperties.Coluna::isAlias)
                .map(WidgetProperties.Coluna::getChave)
                .collect(Collectors.toSet());
        if (chaves.isEmpty() || linhas.isEmpty()) {
            return;
        }
        Set<String> nomes = linhas.stream()
                .flatMap(l -> chaves.stream().map(l::get))
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .collect(Collectors.toSet());
        Map<String, String> aliases = clienteAlias.aliases(nomes);
        for (Map<String, Object> l : linhas) {
            for (String c : chaves) {
                Object nome = l.get(c);
                l.put(c, nome == null ? null : aliases.get(String.valueOf(nome)));
            }
        }
    }

    /**
     * Names whose times to show. By default the caller's own: the cadastro they chose
     * ({@code preferido}), or all of them combined if they never chose. A specific name is allowed if it
     * is one of the caller's own, or for admins any atendente. Unbound callers get 409, which the
     * extension turns into the "Fale com seu administrador" prompt.
     *
     * <p>The names come from Matrix (e-mail binding) or from the other system (admin list), so they
     * are translated to this system's spelling at the end ({@link CorrespondenciaNomes}).
     */
    private List<String> resolverAlvo(Usuario u, String atendente) {
        if (atendente != null && !atendente.isBlank()) {
            if (!u.nomes().contains(atendente) && !UsuarioRepository.ADMIN.equals(u.role())) {
                throw new AuthException(403, "Somente administradores podem ver dados de outros atendentes.");
            }
            return correspondencia.locais(List.of(atendente));
        }
        if (u.nomes().isEmpty()) {
            throw new AuthException(409, "Seu e-mail não está vinculado a nenhum atendente. Fale com seu administrador pelo popup da extensão.");
        }
        return correspondencia.locais(u.preferido() != null ? List.of(u.preferido()) : u.nomes());
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
