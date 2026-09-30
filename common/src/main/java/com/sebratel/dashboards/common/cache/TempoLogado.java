package com.sebratel.dashboards.common.cache;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Time logged into the platform per atendente, from the login sessions ({@code app.widget.tmea.sessoes}).
 * Overlapping sessions count once and everything is clipped to the window [inicio, agora].
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

    private TempoLogado() {}

    /**
     * @param sessoes     {ini, fim} epoch seconds, {@link #SEM_FIM} when there's no logout
     * @param atividades  {ini, fim} of today's calls and pauses (only used with {@code estimarAberta})
     * @param inicio      window start (epoch)
     * @param hojeInicio  today's midnight (epoch)
     * @return null when there's no logged time in the window
     */
    static Logado calcular(List<long[]> sessoes, List<long[]> atividades, long inicio, long hojeInicio, long agora,
                           boolean estimarAberta, ZoneId zona) {
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

        long total = 0;
        Set<LocalDate> dias = new HashSet<>();
        for (long[] t : unidas) {
            long a = Math.max(t[0], inicio);
            long b = Math.min(t[1], agora);
            if (b <= a) {
                continue;
            }
            total += b - a;
            // Dias com login: cada dia que o trecho toca (turno da noite conta nos dois).
            LocalDate d = Instant.ofEpochSecond(a).atZone(zona).toLocalDate();
            LocalDate ultimo = Instant.ofEpochSecond(b - 1).atZone(zona).toLocalDate();
            for (; !d.isAfter(ultimo); d = d.plusDays(1)) {
                dias.add(d);
            }
        }
        if (total == 0) {
            return null;
        }
        Logado l = new Logado();
        l.segundos = total;
        l.dias = dias.size();
        l.sessoes = sessoesNaJanela;
        l.estimadoSegundos = Math.min(estimado, total);
        return l;
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
