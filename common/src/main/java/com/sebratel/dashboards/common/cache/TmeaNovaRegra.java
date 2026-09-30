package com.sebratel.dashboards.common.cache;

import com.sebratel.dashboards.common.config.SemanticDomainProperties;
import com.sebratel.dashboards.common.config.SemanticDomainProperties.Domain;
import com.sebratel.dashboards.common.config.WidgetProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * TMEA under the proposed rule, side by side with the official one ({@link TemposHojeJob}) for the
 * admin tab "TMEA · regra nova" — nothing else uses it yet. Per atendente and day, the gap between
 * consecutive calls (end of the previous — the latest end so far — to the start of the next) counts
 * in full, with no 60-minute cut, minus:
 * <ul>
 *   <li>the registered pauses ({@code app.widget.tmea.pausas}) overlapping the gap;</li>
 *   <li>the time logged off ({@code app.widget.tmea.sessoes}): from a logoff to the next login — or,
 *       when that login isn't recorded (Native only writes a session once it ends), to the next call.</li>
 * </ul>
 * Today's number also counts the gap in progress: from the end of the last call until now, while the
 * atendente hasn't logged off since and for at most {@link #MAX_ANDAMENTO_SEGUNDOS}. Computed in Java from the raw rows (calls, pauses, sessions),
 * today every minute and the last {@code dias-referencia} days every 10 minutes.
 */
@Component
public class TmeaNovaRegra {

    private static final Logger log = LoggerFactory.getLogger(TmeaNovaRegra.class);
    private static final String DOMINIO = "atendimentos";
    private static final String DIMENSAO_ATENDENTE = "atendente";

    /**
     * @param segundosMedios     average idle time per interval
     * @param intervalos         number of intervals (including the one in progress, if any)
     * @param ociosoSegundos     sum of the idle time
     * @param pausaSegundos      pause time taken out of the intervals
     * @param deslogadoSegundos  logged-off time taken out of the intervals
     * @param emAndamentoSegundos idle time of the gap in progress (today only), or null
     * @param longos             intervals with at least {@link #LONGO_SEGUNDOS} of idle time
     * @param maiorOciosoSegundos longest idle time of a single interval
     */
    public record Resultado(double segundosMedios, long intervalos, long ociosoSegundos, long pausaSegundos,
                            long deslogadoSegundos, Long emAndamentoSegundos, long longos, long maiorOciosoSegundos) {}

    /** Idle stretch (after pauses and logged-off time) counted as "ociosidade longa" in the behaviors tab. */
    public static final long LONGO_SEGUNDOS = 30 * 60;

    public record Calculo(Map<String, Resultado> porAtendente, String calculadoEm) {}

    private record Chamada(long ini, long fim, String dia) {}

    private record Intervalo(long ini, long fim) {}

    private static final long SEM_FIM = Long.MIN_VALUE;
    /** The gap in progress stops counting after this (logoffs reach the database late or never). */
    public static final long MAX_ANDAMENTO_SEGUNDOS = 2 * 3600;

    private final JdbcTemplate jdbcTemplate;
    private final SemanticDomainProperties props;
    private final WidgetProperties widget;
    private volatile Calculo hoje;
    private volatile Calculo ultimosDias;

    public TmeaNovaRegra(JdbcTemplate jdbcTemplate, SemanticDomainProperties props, WidgetProperties widget) {
        this.jdbcTemplate = jdbcTemplate;
        this.props = props;
        this.widget = widget;
    }

    /** Today's numbers; null until the first run. */
    public Calculo hoje() {
        return hoje;
    }

    /** Last {@code dias-referencia} days (including today); null until the first run. */
    public Calculo ultimosDias() {
        return ultimosDias;
    }

    public int dias() {
        return Math.max(1, widget.getTmea().getDiasReferencia());
    }

    @Scheduled(initialDelay = 12_000, fixedRate = 60_000)
    public void refreshHoje() {
        Calculo c = calcular("DATE_FORMAT(CURDATE(), '%Y-%m-%d')", "CURDATE()", true);
        if (c != null) {
            hoje = c;
        }
    }

    @Scheduled(initialDelay = 20_000, fixedRate = 600_000)
    public void refreshUltimosDias() {
        int dias = dias();
        Calculo c = calcular("DATE_FORMAT(CURDATE() - INTERVAL " + dias + " DAY, '%Y-%m-%d')",
                "CURDATE() - INTERVAL " + dias + " DAY", false);
        if (c != null) {
            ultimosDias = c;
        }
    }

    /**
     * @param desdeTexto  lower bound for the calls' date column (string form, works for varchar dates)
     * @param desdeData   same bound as a DATE expression, for the pause/session tables
     * @param emAndamento whether to count today's gap in progress
     */
    private Calculo calcular(String desdeTexto, String desdeData, boolean emAndamento) {
        Domain d = props.domain(DOMINIO);
        String atendenteCol = d == null ? null : d.getDimensoes().get(DIMENSAO_ATENDENTE);
        String dataCol = widget.getDataColuna();
        WidgetProperties.Tmea t = widget.getTmea();
        if (d == null || atendenteCol == null || dataCol == null || dataCol.isBlank() || !t.configurado()) {
            return null;
        }
        long inicioMs = System.currentTimeMillis();
        try {
            Map<String, Object> relogio = jdbcTemplate.queryForMap(
                    "SELECT UNIX_TIMESTAMP(NOW()) AS agora, CAST(CURDATE() AS CHAR) AS hoje, CAST(NOW() AS CHAR) AS texto");
            long agora = ((Number) relogio.get("agora")).longValue();
            String hojeDia = (String) relogio.get("hoje");

            String inicio = "(" + t.getInicio() + ")";
            Map<String, List<Chamada>> chamadas = new HashMap<>();
            jdbcTemplate.query("SELECT `" + atendenteCol + "` AS a, " + inicio + " AS ini, (" + t.getFim() + ") AS fim,"
                            + " CAST(DATE(FROM_UNIXTIME(" + inicio + ")) AS CHAR) AS dia FROM `" + d.getTabela() + "`"
                            + " WHERE `" + dataCol + "` >= " + desdeTexto
                            + " AND `" + atendenteCol + "` IS NOT NULL AND `" + atendenteCol + "` <> ''"
                            + " AND " + inicio + " IS NOT NULL",
                    rs -> {
                        long ini = rs.getLong("ini");
                        long fim = rs.getLong("fim");
                        if (rs.wasNull() || fim < ini) {
                            fim = ini;
                        }
                        chamadas.computeIfAbsent(rs.getString("a"), k -> new ArrayList<>())
                                .add(new Chamada(ini, fim, rs.getString("dia")));
                    });

            // Pausa sem fim registrado: resolvida por atendente em fecharPausas.
            Map<String, List<Intervalo>> pausas = intervalos(t.getPausas(), desdeData, SEM_FIM, agora);
            // Sessão aberta (sem logout) vai até "sempre": não há logoff para descontar.
            Map<String, List<Intervalo>> sessoes = intervalos(t.getSessoes(), desdeData, Long.MAX_VALUE, agora);

            Map<String, Resultado> resultado = new HashMap<>();
            chamadas.forEach((atendente, lista) -> {
                lista.sort(Comparator.comparingLong(Chamada::ini));
                Resultado r = porAtendente(lista, fecharPausas(pausas.getOrDefault(atendente, List.of()), lista, agora),
                        deslogado(sessoes.get(atendente)), emAndamento ? hojeDia : null, agora);
                if (r != null) {
                    resultado.put(atendente, r);
                }
            });
            log.debug("TMEA regra nova ({}, desde {}): {} atendentes em {} ms.", d.getTabela(), desdeTexto,
                    resultado.size(), System.currentTimeMillis() - inicioMs);
            return new Calculo(Map.copyOf(resultado), (String) relogio.get("texto"));
        } catch (DataAccessException e) {
            log.warn("Não foi possível calcular o TMEA pela regra nova ({}): {}", d.getTabela(), e.getMessage());
            return null;
        }
    }

    /** atendente -> ranges from a pause/session table; open ranges (fim NULL) end at {@code fimAberto}. */
    private Map<String, List<Intervalo>> intervalos(WidgetProperties.Registros r, String desdeData, long fimAberto,
                                                    long agora) {
        Map<String, List<Intervalo>> out = new HashMap<>();
        if (r == null || !r.configurado()) {
            return out;
        }
        String filtro = r.getFiltro() == null || r.getFiltro().isBlank() ? "" : " AND (" + r.getFiltro() + ")";
        // Um dia antes: pega sessão/pausa que começou antes da janela e ainda estava valendo nela.
        // NULLIF(..., 0): a Matrix grava "0000-00-00 00:00:00" quando o fim não foi registrado.
        jdbcTemplate.query("SELECT `" + r.getAgente() + "` AS a, NULLIF(UNIX_TIMESTAMP(`" + r.getInicio() + "`), 0) AS i,"
                        + " NULLIF(UNIX_TIMESTAMP(`" + r.getFim() + "`), 0) AS f FROM `" + r.getTabela() + "`"
                        + " WHERE `" + r.getInicio() + "` >= " + desdeData + " - INTERVAL 1 DAY" + filtro,
                rs -> {
                    long i = rs.getLong("i");
                    if (rs.wasNull()) {
                        return;
                    }
                    long f = rs.getLong("f");
                    if (rs.wasNull()) {
                        f = SEM_FIM;
                    }
                    if ((f == SEM_FIM || f > i) && i <= agora) {
                        out.computeIfAbsent(rs.getString("a"), k -> new ArrayList<>()).add(new Intervalo(i, f));
                    }
                });
        for (List<Intervalo> lista : out.values()) {
            lista.sort(Comparator.comparingLong(Intervalo::ini));
            if (fimAberto != SEM_FIM) {
                lista.replaceAll(x -> x.fim() == SEM_FIM ? new Intervalo(x.ini(), fimAberto) : x);
            }
        }
        return out;
    }

    /**
     * A pause whose end wasn't recorded lasts until the atendente's next record — next pause or next
     * call, whichever comes first — or until now if there's none (a pause in progress).
     */
    static List<Intervalo> fecharPausas(List<Intervalo> pausas, List<Chamada> chamadas, long agora) {
        if (pausas.stream().noneMatch(p -> p.fim() == SEM_FIM)) {
            return pausas;
        }
        List<Intervalo> out = new ArrayList<>(pausas.size());
        for (int k = 0; k < pausas.size(); k++) {
            Intervalo p = pausas.get(k);
            if (p.fim() != SEM_FIM) {
                out.add(p);
                continue;
            }
            long fim = k + 1 < pausas.size() ? pausas.get(k + 1).ini() : agora;
            for (Chamada c : chamadas) { // ordenadas por início
                if (c.ini() > p.ini()) {
                    fim = Math.min(fim, c.ini());
                    break;
                }
            }
            if (fim > p.ini()) {
                out.add(new Intervalo(p.ini(), fim));
            }
        }
        return out;
    }

    /**
     * Logged-off stretches from the sessions: each end of the merged sessions until the next login
     * ({@link Long#MAX_VALUE} when there's none recorded — the next call then marks the return).
     * Empty when the atendente has no session at all (nothing to go by).
     */
    static List<Intervalo> deslogado(List<Intervalo> sessoes) {
        if (sessoes == null || sessoes.isEmpty()) {
            return List.of();
        }
        List<Intervalo> ordenadas = new ArrayList<>(sessoes);
        ordenadas.sort(Comparator.comparingLong(Intervalo::ini));
        List<Intervalo> unidas = new ArrayList<>();
        for (Intervalo s : ordenadas) {
            Intervalo ultima = unidas.isEmpty() ? null : unidas.get(unidas.size() - 1);
            if (ultima != null && s.ini() <= ultima.fim()) {
                unidas.set(unidas.size() - 1, new Intervalo(ultima.ini(), Math.max(ultima.fim(), s.fim())));
            } else {
                unidas.add(s);
            }
        }
        List<Intervalo> fora = new ArrayList<>();
        for (int i = 0; i < unidas.size(); i++) {
            long fim = unidas.get(i).fim();
            if (fim == Long.MAX_VALUE) {
                break;
            }
            fora.add(new Intervalo(fim, i + 1 < unidas.size() ? unidas.get(i + 1).ini() : Long.MAX_VALUE));
        }
        return fora;
    }

    /**
     * @param hojeDia DB date of today when the gap in progress counts, else null
     */
    static Resultado porAtendente(List<Chamada> chamadas, List<Intervalo> pausas, List<Intervalo> deslogado,
                                  String hojeDia, long agora) {
        long soma = 0;
        long n = 0;
        long pausa = 0;
        long fora = 0;
        Long andamento = null;
        long longos = 0;
        long maior = 0;
        String dia = null;
        long maiorFim = 0;
        long iniAnterior = 0;
        for (int k = 0; k < chamadas.size(); k++) {
            Chamada c = chamadas.get(k);
            if (c.dia().equals(dia)) {
                long[] v = ocioso(maiorFim, c.ini(), iniAnterior, pausas, deslogado);
                longos += v[0] >= LONGO_SEGUNDOS ? 1 : 0;
                maior = Math.max(maior, v[0]);
                soma += v[0];
                pausa += v[1];
                fora += v[2];
                n++;
                maiorFim = Math.max(maiorFim, c.fim());
            } else {
                dia = c.dia();
                maiorFim = c.fim();
            }
            iniAnterior = c.ini();
            boolean ultimaDoDia = k + 1 == chamadas.size() || !chamadas.get(k + 1).dia().equals(dia);
            if (ultimaDoDia && hojeDia != null && hojeDia.equals(dia) && agora > maiorFim
                    && !deslogouDepois(deslogado, iniAnterior, agora)) {
                long[] v = ocioso(maiorFim, agora, iniAnterior, pausas, deslogado);
                if (v[0] <= MAX_ANDAMENTO_SEGUNDOS) { // acima disso: provavelmente encerrou o turno sem logoff registrado
                    andamento = v[0];
                    longos += v[0] >= LONGO_SEGUNDOS ? 1 : 0;
                    maior = Math.max(maior, v[0]);
                    soma += v[0];
                    pausa += v[1];
                    n++;
                }
            }
        }
        if (n == 0) {
            return null;
        }
        return new Resultado((double) soma / n, n, soma, pausa, fora, andamento, longos, maior);
    }

    private static boolean deslogouDepois(List<Intervalo> deslogado, long desde, long ate) {
        for (Intervalo f : deslogado) {
            if (f.ini() >= desde && f.ini() < ate) {
                return true;
            }
        }
        return false;
    }

    /**
     * Idle seconds in [a, b] (b = next call's start): the gap minus logged-off time (logoffs after the
     * previous call started, until the next login or until b) and minus pauses. Returns
     * {idle, pause taken out, logged-off taken out}; overlap between calls counts as 0.
     */
    private static long[] ocioso(long a, long b, long iniAnterior, List<Intervalo> pausas, List<Intervalo> deslogado) {
        if (b <= a) {
            return new long[] {0, 0, 0};
        }
        List<Intervalo> fora = new ArrayList<>();
        for (Intervalo f : deslogado) {
            if (f.ini() >= iniAnterior && f.ini() < b) {
                long i = Math.max(a, f.ini());
                long e = Math.min(b, f.fim());
                if (e > i) {
                    fora.add(new Intervalo(i, e));
                }
            }
        }
        List<Intervalo> todos = new ArrayList<>(fora);
        for (Intervalo p : pausas) {
            long i = Math.max(a, p.ini());
            long e = Math.min(b, p.fim());
            if (e > i) {
                todos.add(new Intervalo(i, e));
            }
        }
        long foraS = medida(fora);
        long descontado = medida(todos);
        return new long[] {(b - a) - descontado, descontado - foraS, foraS};
    }

    /** Total length of the union of the ranges. */
    private static long medida(List<Intervalo> lista) {
        if (lista.isEmpty()) {
            return 0;
        }
        List<Intervalo> ord = new ArrayList<>(lista);
        ord.sort(Comparator.comparingLong(Intervalo::ini));
        long total = 0;
        long ini = ord.get(0).ini();
        long fim = ord.get(0).fim();
        for (Intervalo x : ord.subList(1, ord.size())) {
            if (x.ini() > fim) {
                total += fim - ini;
                ini = x.ini();
                fim = x.fim();
            } else {
                fim = Math.max(fim, x.fim());
            }
        }
        return total + (fim - ini);
    }
}
