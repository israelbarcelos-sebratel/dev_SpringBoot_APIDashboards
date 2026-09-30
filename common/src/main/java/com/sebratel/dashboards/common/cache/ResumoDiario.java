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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Per atendente and per day, for the "Resumo dos últimos dias" table of the atendimentos page: number
 * of calls, the {@code app.widget.tempos} averages (TMA, TME…) with the same expressions as the widget,
 * time in pause (pauses with a recorded end, by start day — same as the "Pausas e comportamentos" tab)
 * and time logged in ({@link TempoLogado}). The last {@link #MAX_DIAS} days before today every 10
 * minutes, today every minute; kept in memory.
 */
@Component
public class ResumoDiario {

    private static final Logger log = LoggerFactory.getLogger(ResumoDiario.class);
    private static final String DOMINIO = "atendimentos";
    private static final String DIMENSAO_ATENDENTE = "atendente";
    /** Longest period the table offers. */
    public static final int MAX_DIAS = 30;

    /** One atendente's day. */
    public static final class Dia {
        public long atendimentos;
        public final Map<String, TemposHojeJob.Tempo> tempos = new LinkedHashMap<>();
        public long pausaSegundos;
        public long logadoSegundos;
        /** Part of {@code logadoSegundos} estimated from the activity (Native session still open). */
        public long estimadoSegundos;
    }

    /** atendente -> day -> numbers; {@code temPausa}/{@code temLogado}: whether those sources are configured. */
    public record Calculo(Map<String, Map<LocalDate, Dia>> porAtendente, boolean temPausa, boolean temLogado,
                          String calculadoEm) {}

    private final JdbcTemplate jdbcTemplate;
    private final SemanticDomainProperties props;
    private final WidgetProperties widget;
    private volatile Calculo hoje;
    private volatile Calculo passados;

    public ResumoDiario(JdbcTemplate jdbcTemplate, SemanticDomainProperties props, WidgetProperties widget) {
        this.jdbcTemplate = jdbcTemplate;
        this.props = props;
        this.widget = widget;
    }

    @Scheduled(initialDelay = 20_000, fixedRate = 60_000)
    public void refreshHoje() {
        Calculo c = calcular("CURDATE()", null);
        if (c != null) {
            hoje = c;
        }
    }

    @Scheduled(initialDelay = 30_000, fixedRate = 600_000)
    public void refreshPassados() {
        Calculo c = calcular("CURDATE() - INTERVAL " + MAX_DIAS + " DAY", "CURDATE()");
        if (c != null) {
            passados = c;
        }
    }

    /** Right after midnight yesterday becomes a past day: don't wait up to 10 minutes for it. */
    @Scheduled(cron = "40 0 0 * * *")
    public void refreshAposMeiaNoite() {
        refreshPassados();
    }

    private static boolean tem(String s) {
        return s != null && !s.isBlank();
    }

    private static boolean nomeValido(String n) {
        return n != null && !n.isBlank() && !"null".equalsIgnoreCase(n.trim());
    }

    /**
     * @param desde DATE expression of the first day
     * @param ate   DATE expression of the day after the last one, or null for "until now"
     */
    private Calculo calcular(String desde, String ate) {
        Domain d = props.domain(DOMINIO);
        String atendenteCol = d == null ? null : d.getDimensoes().get(DIMENSAO_ATENDENTE);
        String dataCol = widget.getDataColuna();
        if (d == null || atendenteCol == null || !tem(dataCol)) {
            return null;
        }
        long t0 = System.currentTimeMillis();
        try {
            ZoneId zona = ZoneId.systemDefault();
            TempoLogado.Relogio relogio = TempoLogado.relogio(jdbcTemplate, desde);
            long fim = ate == null ? relogio.agora()
                    : ((Number) jdbcTemplate.queryForObject("SELECT UNIX_TIMESTAMP(" + ate + ")", Number.class)).longValue();
            Map<String, Map<LocalDate, Dia>> out = new HashMap<>();

            // Atendimentos e médias por dia (mesmas expressões do widget; tempo negativo não conta).
            List<String> metricas = List.copyOf(widget.getTempos().keySet());
            StringBuilder sql = new StringBuilder("SELECT `").append(atendenteCol).append("` AS a, LEFT(`")
                    .append(dataCol).append("`, 10) AS dia, COUNT(*) AS total");
            for (int i = 0; i < metricas.size(); i++) {
                String expr = widget.getTempos().get(metricas.get(i)).expressaoSegundos();
                String valido = "CASE WHEN " + expr + " >= 0 THEN " + expr + " END";
                sql.append(", AVG(").append(valido).append(") AS m").append(i)
                   .append(", COUNT(").append(valido).append(") AS n").append(i);
            }
            sql.append(" FROM `").append(d.getTabela()).append("` WHERE `").append(dataCol).append("` >= DATE_FORMAT(")
               .append(desde).append(", '%Y-%m-%d')");
            if (ate != null) {
                sql.append(" AND `").append(dataCol).append("` < DATE_FORMAT(").append(ate).append(", '%Y-%m-%d')");
            }
            sql.append(" AND `").append(atendenteCol).append("` IS NOT NULL AND `").append(atendenteCol).append("` <> ''")
               .append(" GROUP BY `").append(atendenteCol).append("`, LEFT(`").append(dataCol).append("`, 10)");
            jdbcTemplate.query(sql.toString(), rs -> {
                Dia dia = dia(out, rs.getString("a"), rs.getString("dia"));
                if (dia == null) {
                    return;
                }
                dia.atendimentos += rs.getLong("total");
                for (int i = 0; i < metricas.size(); i++) {
                    long n = rs.getLong("n" + i);
                    if (n > 0) {
                        dia.tempos.put(metricas.get(i), new TemposHojeJob.Tempo(rs.getDouble("m" + i), n));
                    }
                }
            });

            // Tempo em pausa: pausas com fim registrado, no dia em que começaram.
            WidgetProperties.Comportamento cfg = widget.getComportamento();
            boolean temPausa = tem(cfg.getPausasSql());
            Map<String, List<PausasComportamento.Pausa>> pausas = temPausa
                    ? PausasComportamento.lerPausas(jdbcTemplate, cfg.getPausasSql(), desde) : Map.of();
            pausas.forEach((agente, lista) -> {
                for (PausasComportamento.Pausa p : lista) {
                    if (p.fim() != null && p.ini() >= relogio.inicio() && p.ini() < fim) {
                        Dia dia = dia(out, agente, Instant.ofEpochSecond(p.ini()).atZone(zona).toLocalDate());
                        if (dia != null) {
                            dia.pausaSegundos += p.fim() - p.ini();
                        }
                    }
                }
            });

            // Tempo logado por dia; hoje, a sessão aberta da Native é estimada pela atividade.
            WidgetProperties.Registros sess = widget.getTmea().getSessoes();
            boolean temLogado = sess != null && sess.configurado();
            if (temLogado) {
                Map<String, List<long[]>> sessoes = TempoLogado.sessoes(jdbcTemplate, sess, desde);
                boolean estimar = ate == null && cfg.isEstimarSessaoAberta();
                Map<String, List<long[]>> atividades = new HashMap<>();
                if (estimar) {
                    atividades.putAll(TempoLogado.chamadasHoje(jdbcTemplate, widget.getTmea(), d.getTabela(), atendenteCol, dataCol));
                    PausasComportamento.atividadesDePausa(pausas, relogio.hojeInicio(), atividades);
                }
                LocalDate hojeDia = Instant.ofEpochSecond(relogio.hojeInicio()).atZone(zona).toLocalDate();
                Set<String> nomes = new HashSet<>(sessoes.keySet());
                nomes.addAll(atividades.keySet());
                for (String nome : nomes) {
                    long[] estimado = {0};
                    Map<LocalDate, Long> porDia = TempoLogado.porDia(sessoes.getOrDefault(nome, List.of()), atividades.get(nome),
                            relogio.inicio(), fim, relogio.hojeInicio(), relogio.agora(), estimar, zona, estimado);
                    porDia.forEach((data, seg) -> {
                        Dia dia = dia(out, nome, data);
                        if (dia != null) {
                            dia.logadoSegundos += seg;
                            if (data.equals(hojeDia)) {
                                dia.estimadoSegundos = Math.min(estimado[0], dia.logadoSegundos);
                            }
                        }
                    });
                }
            }
            String agora = jdbcTemplate.queryForObject("SELECT CAST(NOW() AS CHAR)", String.class);
            log.debug("Resumo diário ({}, desde {}): {} atendentes em {} ms.", d.getTabela(), desde, out.size(),
                    System.currentTimeMillis() - t0);
            return new Calculo(Map.copyOf(out), temPausa, temLogado, agora);
        } catch (DataAccessException e) {
            log.warn("Não foi possível calcular o resumo diário ({}): {}", d.getTabela(), e.getMessage());
            return null;
        }
    }

    private static Dia dia(Map<String, Map<LocalDate, Dia>> out, String nome, String dia) {
        try {
            return dia == null ? null : dia(out, nome, LocalDate.parse(dia));
        } catch (DateTimeParseException e) {
            return null; // data mal gravada na origem
        }
    }

    private static Dia dia(Map<String, Map<LocalDate, Dia>> out, String nome, LocalDate data) {
        if (!nomeValido(nome)) {
            return null;
        }
        return out.computeIfAbsent(nome, k -> new TreeMap<>()).computeIfAbsent(data, k -> new Dia());
    }

    /**
     * The table for one person (several names combined): each day of the last {@code dias} days with
     * activity, the person's averages and the sector's. Null while nothing was computed yet.
     *
     * @param setorDe atendente name -> sector label (null = no sector: compared with the whole operation)
     */
    public Map<String, Object> resumo(List<String> nomes, int dias, Function<String, String> setorDe) {
        Calculo h = hoje;
        Calculo p = passados;
        if (h == null || p == null) {
            return null;
        }
        LocalDate hojeDia = LocalDate.parse(h.calculadoEm().substring(0, 10));
        LocalDate primeiro = hojeDia.minusDays(Math.max(1, Math.min(dias, MAX_DIAS)) - 1L);
        Map<String, TreeMap<LocalDate, Dia>> todos = new HashMap<>();
        juntar(todos, p, primeiro, hojeDia.minusDays(1));
        juntar(todos, h, hojeDia, hojeDia);

        TreeMap<LocalDate, Dia> meus = somar(nomes.stream().map(todos::get).filter(Objects::nonNull).toList());
        List<Map<String, Object>> linhas = new ArrayList<>();
        meus.forEach((data, dia) -> {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("dia", data.toString());
            l.put("atendimentos", dia.atendimentos);
            l.put("tempos", dia.tempos);
            l.put("pausaSegundos", h.temPausa() ? dia.pausaSegundos : null);
            l.put("logadoSegundos", h.temLogado() ? dia.logadoSegundos : null);
            l.put("estimadoSegundos", dia.estimadoSegundos);
            linhas.add(l);
        });

        String setor = nomes.stream().map(setorDe).filter(Objects::nonNull).findFirst().orElse(null);
        Set<String> meusNomes = new HashSet<>(nomes);
        List<Medias> colegas = new ArrayList<>();
        todos.forEach((nome, porDia) -> {
            if (!meusNomes.contains(nome) && (setor == null || setor.equals(setorDe.apply(nome)))) {
                Medias m = medias(diasFechados(porDia, hojeDia));
                if (m.diasComAtividade > 0) {
                    colegas.add(m);
                }
            }
        });

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("dias", (int) (hojeDia.toEpochDay() - primeiro.toEpochDay() + 1));
        resp.put("desde", primeiro.toString());
        resp.put("hoje", hojeDia.toString());
        resp.put("temPausa", h.temPausa());
        resp.put("temLogado", h.temLogado());
        resp.put("linhas", linhas);
        resp.put("voce", medias(diasFechados(meus, hojeDia)).mapa(h));
        Map<String, Object> ref = mediaDosColegas(colegas, h);
        ref.put("setor", setor);
        ref.put("atendentes", colegas.size());
        resp.put("setor", ref);
        resp.put("calculadoEm", h.calculadoEm());
        return resp;
    }

    /**
     * Days for the averages: without today (still in progress — half a day would pull the average
     * down), unless today is all there is.
     */
    private static TreeMap<LocalDate, Dia> diasFechados(TreeMap<LocalDate, Dia> porDia, LocalDate hojeDia) {
        TreeMap<LocalDate, Dia> antes = new TreeMap<>(porDia.headMap(hojeDia, false));
        return antes.isEmpty() ? porDia : antes;
    }

    private static void juntar(Map<String, TreeMap<LocalDate, Dia>> todos, Calculo c, LocalDate de, LocalDate ate) {
        c.porAtendente().forEach((nome, porDia) -> porDia.forEach((data, dia) -> {
            if (!data.isBefore(de) && !data.isAfter(ate)) {
                todos.computeIfAbsent(nome, k -> new TreeMap<>()).put(data, dia);
            }
        }));
    }

    /** Several cadastros of one person as one: counts and seconds add up, averages weighted by rows. */
    private static TreeMap<LocalDate, Dia> somar(List<TreeMap<LocalDate, Dia>> listas) {
        if (listas.size() == 1) {
            return listas.get(0);
        }
        TreeMap<LocalDate, Dia> out = new TreeMap<>();
        for (TreeMap<LocalDate, Dia> porDia : listas) {
            porDia.forEach((data, d) -> {
                Dia s = out.computeIfAbsent(data, k -> new Dia());
                s.atendimentos += d.atendimentos;
                s.pausaSegundos += d.pausaSegundos;
                s.logadoSegundos += d.logadoSegundos;
                s.estimadoSegundos += d.estimadoSegundos;
                d.tempos.forEach((k, t) -> s.tempos.merge(k, t, (a, b) -> new TemposHojeJob.Tempo(
                        (a.segundosMedios() * a.amostras() + b.segundosMedios() * b.amostras()) / (a.amostras() + b.amostras()),
                        a.amostras() + b.amostras())));
            });
        }
        return out;
    }

    /**
     * One person's per-day averages over the period: calls per day with calls, TMA/TME over all the
     * period's calls, pause per day worked (day with calls), logged time per day with login.
     */
    private record Medias(Double atendimentos, Map<String, Double> tempos, Double pausaSegundos, Double logadoSegundos,
                          int diasComAtividade) {
        Map<String, Object> mapa(Calculo c) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("atendimentos", atendimentos);
            m.put("tempos", tempos);
            m.put("pausaSegundos", c.temPausa() ? pausaSegundos : null);
            m.put("logadoSegundos", c.temLogado() ? logadoSegundos : null);
            return m;
        }
    }

    private static Medias medias(TreeMap<LocalDate, Dia> porDia) {
        long atend = 0;
        long diasAtend = 0;
        long pausa = 0;
        long logado = 0;
        long diasLogado = 0;
        int atividade = 0;
        Map<String, double[]> tempos = new LinkedHashMap<>();
        for (Dia d : porDia.values()) {
            if (d.atendimentos > 0) {
                atend += d.atendimentos;
                diasAtend++;
                pausa += d.pausaSegundos;
            }
            if (d.logadoSegundos > 0) {
                logado += d.logadoSegundos;
                diasLogado++;
            }
            if (d.atendimentos > 0 || d.logadoSegundos > 0) {
                atividade++;
            }
            d.tempos.forEach((k, t) -> {
                double[] a = tempos.computeIfAbsent(k, x -> new double[2]);
                a[0] += t.segundosMedios() * t.amostras();
                a[1] += t.amostras();
            });
        }
        Map<String, Double> medias = new LinkedHashMap<>();
        tempos.forEach((k, a) -> medias.put(k, a[0] / a[1]));
        return new Medias(diasAtend == 0 ? null : (double) atend / diasAtend, medias,
                diasAtend == 0 ? null : (double) pausa / diasAtend, diasLogado == 0 ? null : (double) logado / diasLogado,
                atividade);
    }

    /** Mean of the colleagues' averages, each colleague weighing the same (like the TMEA reference). */
    private static Map<String, Object> mediaDosColegas(List<Medias> colegas, Calculo c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("atendimentos", media(colegas.stream().map(Medias::atendimentos).toList()));
        Map<String, Double> tempos = new LinkedHashMap<>();
        Set<String> chaves = new HashSet<>();
        colegas.forEach(x -> chaves.addAll(x.tempos().keySet()));
        for (String k : chaves) {
            tempos.put(k, media(colegas.stream().map(x -> x.tempos().get(k)).toList()));
        }
        m.put("tempos", tempos);
        m.put("pausaSegundos", c.temPausa() ? media(colegas.stream().map(Medias::pausaSegundos).toList()) : null);
        m.put("logadoSegundos", c.temLogado() ? media(colegas.stream().map(Medias::logadoSegundos).toList()) : null);
        return m;
    }

    private static Double media(List<Double> valores) {
        double soma = 0;
        int n = 0;
        for (Double v : valores) {
            if (v != null) {
                soma += v;
                n++;
            }
        }
        return n == 0 ? null : soma / n;
    }
}
