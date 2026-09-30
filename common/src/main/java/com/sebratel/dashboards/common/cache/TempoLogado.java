package com.sebratel.dashboards.common.cache;

import com.sebratel.dashboards.common.config.WidgetProperties;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Time logged into the platform per atendente, from the login sessions ({@code app.widget.tmea.sessoes}).
 * Overlapping sessions count once and everything is clipped to the window.
 *
 * <ul>
 *   <li>A session without logout (the Matrix writes the row at login) lasts until the same person's
 *       next login, at most {@link #MAX_ABERTA_SEGUNDOS}, never past now.</li>
 *   <li>The Native only writes the session at logoff. With {@code estimarAberta}, whoever had activity
 *       (call or pause) today after the last recorded logoff is taken as logged in from that first
 *       activity until now — or until the last activity, when it ended more than
 *       {@link TmeaNovaRegra#MAX_ANDAMENTO_SEGUNDOS} ago (shift over, logoff not in the database yet).</li>
 * </ul>
 */
public final class TempoLogado {

    /** A session without logout never counts more than this. */
    public static final long MAX_ABERTA_SEGUNDOS = 12 * 3600;
    /** Epoch marker for a session end that wasn't recorded. */
    public static final long SEM_FIM = Long.MIN_VALUE;

    /** Per atendente: seconds logged, days with login, sessions started in the window, estimated part. */
    public static final class Logado {
        public long segundos;
        public long dias;
        public long sessoes;
        public long estimadoSegundos;
    }

    /** DB clock for a window: its start, today's midnight and now (epoch seconds). */
    public record Relogio(long inicio, long hojeInicio, long agora) {}

    /** Merged logged-in stretches, how many sessions started at/after {@code inicio}, the estimated part. */
    private record Trechos(List<long[]> unidas, long sessoes, long estimado) {}

    private TempoLogado() {}

    /** @param desde DATE expression of the window's start */
    static Relogio relogio(JdbcTemplate jdbc, String desde) {
        Map<String, Object> r = jdbc.queryForMap("SELECT UNIX_TIMESTAMP(" + desde + ") AS inicio,"
                + " UNIX_TIMESTAMP(CURDATE()) AS hoje, UNIX_TIMESTAMP(NOW()) AS agora");
        return new Relogio(((Number) r.get("inicio")).longValue(), ((Number) r.get("hoje")).longValue(),
                ((Number) r.get("agora")).longValue());
    }

    /**
     * atendente -> {ini, fim} of the sessions that started from one day before {@code desde} (a session
     * begun before the window may still be open in it); {@link #SEM_FIM} when there's no logout.
     */
    static Map<String, List<long[]>> sessoes(JdbcTemplate jdbc, WidgetProperties.Registros sess, String desde) {
        Map<String, List<long[]>> out = new HashMap<>();
        if (sess == null || !sess.configurado()) {
            return out;
        }
        String filtro = sess.getFiltro() == null || sess.getFiltro().isBlank() ? "" : " AND (" + sess.getFiltro() + ")";
        // NULLIF(..., 0): "0000-00-00 00:00:00" quando o logout não foi registrado.
        jdbc.query("SELECT `" + sess.getAgente() + "` AS a, NULLIF(UNIX_TIMESTAMP(`" + sess.getInicio() + "`), 0) AS i,"
                        + " NULLIF(UNIX_TIMESTAMP(`" + sess.getFim() + "`), 0) AS f FROM `" + sess.getTabela() + "`"
                        + " WHERE `" + sess.getInicio() + "` >= " + desde + " - INTERVAL 1 DAY AND `" + sess.getInicio()
                        + "` <= NOW()" + filtro,
                rs -> {
                    long i = rs.getLong("i");
                    if (rs.wasNull()) {
                        return;
                    }
                    long f = rs.getLong("f");
                    long fim = rs.wasNull() ? SEM_FIM : f;
                    out.computeIfAbsent(rs.getString("a"), k -> new ArrayList<>()).add(new long[] {i, fim});
                });
        return out;
    }

    /** atendente -> {ini, fim} of today's calls (the activity that estimates the Native's open session). */
    static Map<String, List<long[]>> chamadasHoje(JdbcTemplate jdbc, WidgetProperties.Tmea t, String tabela,
                                                  String atendenteCol, String dataCol) {
        Map<String, List<long[]>> out = new HashMap<>();
        if (t.getInicio() == null || t.getInicio().isBlank() || t.getFim() == null || t.getFim().isBlank()) {
            return out;
        }
        String ini = "(" + t.getInicio() + ")";
        jdbc.query("SELECT `" + atendenteCol + "` AS a, " + ini + " AS ini, (" + t.getFim() + ") AS fim FROM `" + tabela
                        + "` WHERE `" + dataCol + "` >= DATE_FORMAT(CURDATE(), '%Y-%m-%d') AND `" + atendenteCol
                        + "` IS NOT NULL AND `" + atendenteCol + "` <> '' AND " + ini + " IS NOT NULL",
                rs -> {
                    long i = rs.getLong("ini");
                    long f = rs.getLong("fim");
                    long fim = rs.wasNull() ? i : f;
                    out.computeIfAbsent(rs.getString("a"), k -> new ArrayList<>()).add(new long[] {i, fim});
                });
        return out;
    }

    /**
     * @param sessoes     {ini, fim} epoch seconds, {@link #SEM_FIM} when there's no logout
     * @param atividades  {ini, fim} of today's calls and pauses (only used with {@code estimarAberta})
     * @return null when there's no logged time in [inicio, agora]
     */
    static Logado calcular(List<long[]> sessoes, List<long[]> atividades, long inicio, long hojeInicio, long agora,
                           boolean estimarAberta, ZoneId zona) {
        Trechos t = montar(sessoes, atividades, inicio, hojeInicio, agora, estimarAberta);
        Map<LocalDate, Long> dias = porDia(t.unidas(), inicio, agora, zona);
        long total = dias.values().stream().mapToLong(Long::longValue).sum();
        if (total == 0) {
            return null;
        }
        Logado l = new Logado();
        l.segundos = total;
        l.dias = dias.size();
        l.sessoes = t.sessoes();
        l.estimadoSegundos = Math.min(t.estimado(), total);
        return l;
    }

    /**
     * Seconds logged per day in [inicio, fim) (a night shift counts in both days); with
     * {@code estimarAberta}, today's estimated part goes to {@code estimadoHoje[0]}.
     */
    static Map<LocalDate, Long> porDia(List<long[]> sessoes, List<long[]> atividades, long inicio, long fim,
                                       long hojeInicio, long agora, boolean estimarAberta, ZoneId zona,
                                       long[] estimadoHoje) {
        Trechos t = montar(sessoes, atividades, inicio, hojeInicio, agora, estimarAberta);
        if (estimadoHoje != null) {
            estimadoHoje[0] = t.estimado();
        }
        return porDia(t.unidas(), inicio, Math.min(fim, agora), zona);
    }

    private static Trechos montar(List<long[]> sessoes, List<long[]> atividades, long inicio, long hojeInicio,
                                  long agora, boolean estimarAberta) {
        List<long[]> ord = new ArrayList<>(sessoes);
        ord.sort(Comparator.comparingLong(s -> s[0]));
        List<long[]> trechos = new ArrayList<>();
        long sessoesNaJanela = 0;
        for (int k = 0; k < ord.size(); k++) {
            long ini = ord.get(k)[0];
            long fim = ord.get(k)[1];
            if (fim == SEM_FIM) {
                fim = Math.min(ini + MAX_ABERTA_SEGUNDOS, k + 1 < ord.size() ? ord.get(k + 1)[0] : Long.MAX_VALUE);
            }
            fim = Math.min(fim, agora);
            if (fim > ini) {
                trechos.add(new long[] {ini, fim});
                if (ini >= inicio) {
                    sessoesNaJanela++;
                }
            }
        }
        List<long[]> unidas = unir(trechos);

        long estimado = 0;
        if (estimarAberta && atividades != null && !atividades.isEmpty()) {
            long desde = Math.max(hojeInicio, unidas.isEmpty() ? hojeInicio : unidas.get(unidas.size() - 1)[1]);
            long a0 = Long.MAX_VALUE;
            long a1 = Long.MIN_VALUE;
            for (long[] a : atividades) {
                if (a[0] >= desde && a[0] <= agora) {
                    a0 = Math.min(a0, a[0]);
                    a1 = Math.max(a1, Math.max(a[0], a[1]));
                }
            }
            if (a0 != Long.MAX_VALUE) {
                long fim = agora - a1 <= TmeaNovaRegra.MAX_ANDAMENTO_SEGUNDOS ? agora : Math.min(a1, agora);
                if (fim > a0) {
                    estimado = fim - a0;
                    trechos.add(new long[] {a0, fim});
                    sessoesNaJanela++;
                    unidas = unir(trechos);
                }
            }
        }
        return new Trechos(unidas, sessoesNaJanela, estimado);
    }

    /** Merged stretches clipped to [inicio, fim), split at midnight: day -> seconds (only days with time). */
    private static Map<LocalDate, Long> porDia(List<long[]> unidas, long inicio, long fim, ZoneId zona) {
        Map<LocalDate, Long> out = new TreeMap<>();
        for (long[] t : unidas) {
            long a = Math.max(t[0], inicio);
            long b = Math.min(t[1], fim);
            while (b > a) {
                LocalDate dia = Instant.ofEpochSecond(a).atZone(zona).toLocalDate();
                long meiaNoite = dia.plusDays(1).atStartOfDay(zona).toEpochSecond();
                long ate = Math.min(b, meiaNoite);
                out.merge(dia, ate - a, Long::sum);
                a = ate;
            }
        }
        return out;
    }

    private static List<long[]> unir(List<long[]> trechos) {
        List<long[]> ord = new ArrayList<>(trechos);
        ord.sort(Comparator.comparingLong(t -> t[0]));
        List<long[]> out = new ArrayList<>();
        for (long[] t : ord) {
            long[] ultimo = out.isEmpty() ? null : out.get(out.size() - 1);
            if (ultimo != null && t[0] <= ultimo[1]) {
                ultimo[1] = Math.max(ultimo[1], t[1]);
            } else {
                out.add(new long[] {t[0], t[1]});
            }
        }
        return out;
    }
}
