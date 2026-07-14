package com.sebratel.dashboards.common.semantic;

import com.sebratel.dashboards.common.config.SemanticDomainProperties;
import com.sebratel.dashboards.common.config.SemanticDomainProperties.Domain;
import com.sebratel.dashboards.common.dto.BreakdownTimeSeriesResponse;
import com.sebratel.dashboards.common.dto.TimeSeriesResponse;
import com.sebratel.dashboards.common.semantic.MetricResponse.Periodo;
import com.sebratel.dashboards.common.semantic.MetricResponse.Visualizacao;
import com.sebratel.dashboards.common.stats.CategoricalStats;
import com.sebratel.dashboards.common.stats.Insight;
import com.sebratel.dashboards.common.stats.TimeSeriesPoint;
import com.sebratel.dashboards.common.stats.TimeSeriesResult;
import com.sebratel.dashboards.common.web.TableDataService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Translates a business question ("volume por semana", "satisfação ao longo do tempo") into one or
 * more calls to the generic {@link TableDataService} and repackages the result into the friendly,
 * self-describing {@link MetricResponse} envelope. This is the thin layer the semantic controllers
 * sit on: it holds no SQL and no chart code — only the mapping from meaning to the base layer and
 * back into human-facing shape.
 */
@Service
public class SemanticService {

    private final TableDataService data;
    private final SemanticDomainProperties props;

    public SemanticService(TableDataService data, SemanticDomainProperties props) {
        this.data = data;
        this.props = props;
    }

    /** Score -> sentiment label + emoji, worst to best (see the satisfaction data model). */
    private static final Map<String, String> SENTIMENTOS = Map.of(
            "1", "Irritado 😡",
            "2", "Frustrado 😟",
            "3", "Aliviado 😌",
            "4", "Satisfeito 🙂",
            "5", "Encantado 🤩");

    // ---------------------------------------------------------------- discovery

    /** The plain-language map of everything this service exposes — {@code GET /dominios}. */
    public DomainCatalog catalogo() {
        List<DomainCatalog.DomainInfo> dominios = new ArrayList<>();
        for (Map.Entry<String, Domain> e : props.getDomains().entrySet()) {
            String nome = e.getKey();
            Domain d = e.getValue();
            dominios.add(new DomainCatalog.DomainInfo(
                    nome, d.getTitulo(), d.getDescricao(),
                    metricasDe(nome, d),
                    new ArrayList<>(d.getDimensoes().keySet())));
        }
        return new DomainCatalog(dominios);
    }

    private List<DomainCatalog.MetricInfo> metricasDe(String nome, Domain d) {
        List<DomainCatalog.MetricInfo> m = new ArrayList<>();
        String base = "/" + nome;
        m.add(new DomainCatalog.MetricInfo(base + "/resumo", "Resumo", Visualizacao.KPI));
        m.add(new DomainCatalog.MetricInfo(base + (d.getNotaColumn() != null ? "/evolucao" : "/volume"),
                d.getNotaColumn() != null ? "Evolução ao longo do tempo" : "Volume ao longo do tempo",
                d.getNotaColumn() != null ? Visualizacao.MULTILINHA : Visualizacao.LINHA));
        for (String dim : d.getDimensoes().keySet()) {
            m.add(new DomainCatalog.MetricInfo(base + "/por/" + dim, "Por " + dim, Visualizacao.BARRA));
        }
        return m;
    }

    // ---------------------------------------------------------------- resumo (KPIs)

    public MetricResponse resumo(String dominio, Map<String, String> filtros, Integer meses) {
        Domain d = require(dominio);
        Periodo periodo = periodo(d, filtros, meses);
        Map<String, Object> resumo = new LinkedHashMap<>();
        List<String> insights = new ArrayList<>();

        resumo.put("total", data.count(d.getTabela(), filtros, meses));

        // Variation only when the data set is time-based (windowBounds is null otherwise).
        if (periodo.de() != null) {
            TimeSeriesResponse ts = data.getTimeSeries(d.getTabela(), null, "month", filtros, meses);
            TimeSeriesResult s = ts.series();
            if (s.periodOverPeriodChangePct() != null) {
                resumo.put("variacaoPct", round(s.periodOverPeriodChangePct()));
                resumo.put("tendencia", tendencia(s.periodOverPeriodChangePct()));
            }
            insights.addAll(mensagens(ts.insights()));
        }

        if (d.getNotaColumn() != null) {
            CategoricalStats notas = data.categoricalColumn(d.getTabela(), d.getNotaColumn(), filtros, meses);
            resumo.put("notaMedia", round(mediaPonderada(notas)));
            resumo.put("percentualPositivas", round(percentualPositivas(notas)));
        }

        return new MetricResponse(
                "Resumo · " + d.getTitulo(),
                "Panorama do domínio no período selecionado.",
                periodo, Visualizacao.KPI, d.getUnidade(),
                null, resumo, insights);
    }

    // ---------------------------------------------------------------- volume / evolução

    public MetricResponse volume(String dominio, String por, Map<String, String> filtros, Integer meses) {
        Domain d = require(dominio);
        TimeSeriesResponse resp = data.getTimeSeries(d.getTabela(), null, granularidade(por), filtros, meses);
        TimeSeriesResult s = resp.series();

        Map<String, Object> dados = new LinkedHashMap<>();
        dados.put("rotulos", s.points().stream().map(TimeSeriesPoint::period).toList());
        dados.put("valores", s.points().stream().map(TimeSeriesPoint::count).toList());
        dados.put("mediaMovel", s.points().stream().map(TimeSeriesPoint::movingAverage).toList());

        Map<String, Object> resumo = new LinkedHashMap<>();
        resumo.put("total", s.points().stream().mapToLong(TimeSeriesPoint::count).sum());
        if (s.periodOverPeriodChangePct() != null) {
            resumo.put("variacaoPct", round(s.periodOverPeriodChangePct()));
            resumo.put("tendencia", tendencia(s.periodOverPeriodChangePct()));
        }
        if (s.yearOverYearChangePct() != null) {
            resumo.put("variacaoAnualPct", round(s.yearOverYearChangePct()));
        }

        return new MetricResponse(
                "Volume · " + d.getTitulo(),
                "Quantos " + d.getUnidade() + " por " + por + " ao longo do período.",
                periodo(d, filtros, meses), Visualizacao.LINHA, d.getUnidade(),
                dados, resumo, mensagens(resp.insights()));
    }

    /** Satisfaction-style evolution: one line per score, over time. */
    public MetricResponse evolucao(String dominio, String por, Map<String, String> filtros, Integer meses) {
        Domain d = require(dominio);
        if (d.getNotaColumn() == null) {
            throw new IllegalArgumentException("O domínio '" + dominio + "' não tem série por nota configurada.");
        }
        BreakdownTimeSeriesResponse bd =
                data.getTimeSeriesBreakdown(d.getTabela(), d.getNotaColumn(), granularidade(por), filtros, meses);

        List<Map<String, Object>> linhas = bd.series().stream().map(serie -> {
            Map<String, Object> linha = new LinkedHashMap<>();
            linha.put("nota", serie.key());
            linha.put("sentimento", SENTIMENTOS.getOrDefault(serie.key(), serie.key()));
            linha.put("valores", serie.counts());
            return linha;
        }).toList();

        Map<String, Object> dados = new LinkedHashMap<>();
        dados.put("rotulos", bd.periods());
        dados.put("linhas", linhas);

        return new MetricResponse(
                "Evolução da satisfação · " + d.getTitulo(),
                "Uma linha por nota (de Irritado a Encantado) ao longo do período.",
                periodo(d, filtros, meses), Visualizacao.MULTILINHA, "avaliações",
                dados, Map.of(), List.of());
    }

    // ---------------------------------------------------------------- por dimensão

    public MetricResponse porDimensao(String dominio, String dimensao, Map<String, String> filtros, Integer meses) {
        Domain d = require(dominio);
        String coluna = requireDimensao(d, dimensao);
        CategoricalStats cat = data.categoricalColumn(d.getTabela(), coluna, filtros, meses);
        boolean satisfacao = coluna.equals(d.getNotaColumn());

        List<Map<String, Object>> categorias = cat.distribution().stream().map(share -> {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("rotulo", satisfacao ? SENTIMENTOS.getOrDefault(share.label(), share.label()) : share.label());
            c.put("valor", share.count());
            c.put("percentual", round(share.percentage()));
            return c;
        }).toList();

        Map<String, Object> dados = new LinkedHashMap<>();
        dados.put("categorias", categorias);

        Map<String, Object> resumo = new LinkedHashMap<>();
        resumo.put("distintos", cat.distinctCount());
        if (cat.mode() != null) {
            resumo.put("predominante", satisfacao ? SENTIMENTOS.getOrDefault(cat.mode(), cat.mode()) : cat.mode());
        }

        // Few categories read better as a donut; many, as a ranked bar list.
        String viz = cat.distinctCount() <= 5 ? Visualizacao.ROSCA : Visualizacao.BARRA;

        return new MetricResponse(
                "Por " + dimensao + " · " + d.getTitulo(),
                "Distribuição de " + d.getUnidade() + " por " + dimensao + ".",
                periodo(d, filtros, meses), viz, d.getUnidade(),
                dados, resumo, List.of());
    }

    /** Histogram of a numeric dimension (e.g. seconds paused / logged in) over the scoped window. */
    public MetricResponse duracao(String dominio, String dimensao, String unidade,
                                  Map<String, String> filtros, Integer meses) {
        Domain d = require(dominio);
        String coluna = requireDimensao(d, dimensao);
        com.sebratel.dashboards.common.stats.DescriptiveStats st =
                data.numericColumn(d.getTabela(), coluna, filtros, meses);

        List<Map<String, Object>> bins = st.histogram().stream().map(b -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("de", b.rangeStart());
            m.put("ate", b.rangeEnd());
            m.put("contagem", b.count());
            return m;
        }).toList();

        Map<String, Object> dados = new LinkedHashMap<>();
        dados.put("bins", bins);

        Map<String, Object> resumo = new LinkedHashMap<>();
        resumo.put("media", round(st.mean()));
        resumo.put("mediana", round(st.median()));
        resumo.put("minimo", round(st.min()));
        resumo.put("maximo", round(st.max()));

        return new MetricResponse(
                "Distribuição de " + dimensao + " · " + d.getTitulo(),
                "Como se distribui " + dimensao + " (em " + unidade + ") no período.",
                periodo(d, filtros, meses), Visualizacao.HISTOGRAMA, unidade,
                dados, resumo, List.of());
    }

    /** Mean duration per configured time dimension (fila / TMIC / TMIA), in seconds. */
    public MetricResponse tempos(String dominio, List<String> dimensoes, Map<String, String> filtros, Integer meses) {
        Domain d = require(dominio);
        List<Map<String, Object>> categorias = new ArrayList<>();
        for (String dim : dimensoes) {
            String coluna = d.getDimensoes().get(dim);
            if (coluna == null) {
                continue;
            }
            CategoricalStats.DurationSummary dur = data.categoricalColumn(d.getTabela(), coluna, filtros, meses)
                    .durationSummary();
            if (dur != null) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("rotulo", dim);
                c.put("segundosMedios", round(dur.meanSeconds()));
                categorias.add(c);
            }
        }
        Map<String, Object> dados = new LinkedHashMap<>();
        dados.put("categorias", categorias);
        return new MetricResponse(
                "Tempos · " + d.getTitulo(),
                "Tempo médio de espera em fila e de resposta, em segundos.",
                periodo(d, filtros, meses), Visualizacao.BARRA, "segundos",
                dados, Map.of(), List.of());
    }

    // ---------------------------------------------------------------- helpers

    private Domain require(String dominio) {
        Domain d = props.domain(dominio);
        if (d == null) {
            throw new UnknownDomainException(dominio);
        }
        return d;
    }

    private String requireDimensao(Domain d, String dimensao) {
        String coluna = d.getDimensoes().get(dimensao);
        if (coluna == null) {
            throw new IllegalArgumentException("Dimensão desconhecida: " + dimensao);
        }
        return coluna;
    }

    private Periodo periodo(Domain d, Map<String, String> filtros, Integer meses) {
        String[] bounds = data.windowBounds(d.getTabela(), filtros, meses);
        boolean intervaloExplicito = filtros != null && (filtros.get("inicio") != null || filtros.get("fim") != null);
        String rotulo = intervaloExplicito
                ? "período selecionado"
                : (meses != null && meses > 0)
                        ? "últimos " + meses + (meses == 1 ? " mês" : " meses")
                        : "últimas 6 semanas";
        return new Periodo(bounds == null ? null : bounds[0], bounds == null ? null : bounds[1], rotulo);
    }

    private static String granularidade(String por) {
        if (por == null) {
            return "week";
        }
        return switch (por.toLowerCase()) {
            case "hora" -> "hour";
            case "dia" -> "day";
            case "mes", "mês" -> "month";
            default -> "week";
        };
    }

    private static String tendencia(double variacaoPct) {
        if (variacaoPct > 1) {
            return "alta";
        }
        if (variacaoPct < -1) {
            return "baixa";
        }
        return "estavel";
    }

    private static List<String> mensagens(List<Insight> insights) {
        return insights.stream().map(Insight::message).toList();
    }

    /** Weighted average of numeric score labels ("1".."5") by their counts. */
    private static double mediaPonderada(CategoricalStats cat) {
        double soma = 0;
        long n = 0;
        for (CategoricalStats.CategoryShare share : cat.distribution()) {
            try {
                soma += Double.parseDouble(share.label()) * share.count();
                n += share.count();
            } catch (NumberFormatException ignored) {
                // non-numeric label (e.g. "(vazio)") doesn't contribute to the average
            }
        }
        return n == 0 ? 0 : soma / n;
    }

    /** Share of votes scoring 4 or 5, as a percentage. */
    private static double percentualPositivas(CategoricalStats cat) {
        long positivas = 0;
        long total = 0;
        for (CategoricalStats.CategoryShare share : cat.distribution()) {
            total += share.count();
            try {
                if (Double.parseDouble(share.label()) >= 4) {
                    positivas += share.count();
                }
            } catch (NumberFormatException ignored) {
                // ignore non-numeric labels
            }
        }
        return total == 0 ? 0 : (positivas * 100.0) / total;
    }

    private static Double round(Double v) {
        return v == null ? null : Math.round(v * 10.0) / 10.0;
    }
}
