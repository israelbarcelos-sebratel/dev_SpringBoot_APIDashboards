package com.sebratel.dashboards.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the Chrome extension's floating widget ({@code /ext/widget}) shows, bound from
 * {@code app.widget}. Kept apart from {@code app.domains.atendimentos.tempos} (which feeds the
 * dashboards' {@code /atendimentos/tempos}) so the widget can show the same metric names — TMA/TME —
 * for both systems even though matrix has no ready-made columns for them.
 *
 * <pre>
 * app:
 *   widget:
 *     data-coluna: data_entrada          # defines "hoje" / the 6-week window
 *     tempos:
 *       tma: { inicio: data_atendimento, fim: data_finalizacao }   # gap between two datetimes
 *       tme: { hms: espera, meta-segundos: 25 }                    # "HH:MM:SS" varchar + limite
 *     tmea:                               # intervalo entre atendimentos (epoch seconds, SQL)
 *       inicio: "UNIX_TIMESTAMP(`data_hora`) + TIME_TO_SEC(`espera`)"
 *       fim: "UNIX_TIMESTAMP(`data_hora`) + TIME_TO_SEC(`espera`) + TIME_TO_SEC(`atendimento`)"
 *     detalhe:                            # columns of the per-call table (/ext/widget/detalhe)
 *       - { chave: protocolo, rotulo: Protocolo, coluna: protocolo }
 *       - { chave: cliente, rotulo: Cliente, coluna: contato, alias: true }  # pseudonymized
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "app.widget")
public class WidgetProperties {

    private String dataColuna;
    private Map<String, Metrica> tempos = new LinkedHashMap<>();
    private List<Coluna> detalhe = new ArrayList<>();
    private int detalheLimite = 2000;
    private Tmea tmea = new Tmea();

    public String getDataColuna() {
        return dataColuna;
    }

    public void setDataColuna(String dataColuna) {
        this.dataColuna = dataColuna;
    }

    public Map<String, Metrica> getTempos() {
        return tempos;
    }

    public void setTempos(Map<String, Metrica> tempos) {
        this.tempos = tempos;
    }

    public List<Coluna> getDetalhe() {
        return detalhe;
    }

    public void setDetalhe(List<Coluna> detalhe) {
        this.detalhe = detalhe;
    }

    private Comportamento comportamento = new Comportamento();

    public Comportamento getComportamento() {
        return comportamento;
    }

    public void setComportamento(Comportamento comportamento) {
        this.comportamento = comportamento;
    }

    public Tmea getTmea() {
        return tmea;
    }

    public void setTmea(Tmea tmea) {
        this.tmea = tmea;
    }

    public int getDetalheLimite() {
        return detalheLimite;
    }

    public void setDetalheLimite(int detalheLimite) {
        this.detalheLimite = detalheLimite;
    }

    /**
     * One column of the per-call table: {@code chave} in the JSON, {@code rotulo} in the UI.
     * {@code alias: true} replaces the value with its {@code ClienteAlias} pseudonym (customer names).
     */
    public static class Coluna {
        private String chave;
        private String rotulo;
        private String coluna;
        private boolean alias;

        public boolean isAlias() {
            return alias;
        }

        public void setAlias(boolean alias) {
            this.alias = alias;
        }

        public String getChave() {
            return chave;
        }

        public void setChave(String chave) {
            this.chave = chave;
        }

        public String getRotulo() {
            return rotulo;
        }

        public void setRotulo(String rotulo) {
            this.rotulo = rotulo;
        }

        public String getColuna() {
            return coluna;
        }

        public void setColuna(String coluna) {
            this.coluna = coluna;
        }
    }

    /**
     * Either {@code hms} (an "HH:MM:SS" column) or {@code inicio}+{@code fim} (two datetime columns).
     * {@code formula} is the human explanation shown next to the metric in the per-call table;
     * {@code metaSegundos} the productivity limit (above it the extension shows the time as exceeded).
     */
    public static class Metrica {
        private String formula;
        private Integer metaSegundos;
        private String hms;
        private String inicio;
        private String fim;

        /** SQL for this metric in seconds; the column names come only from application.yml. */
        public String expressaoSegundos() {
            if (hms != null) {
                return "TIME_TO_SEC(`" + hms + "`)";
            }
            if (inicio != null && fim != null) {
                return "TIMESTAMPDIFF(SECOND, `" + inicio + "`, `" + fim + "`)";
            }
            throw new IllegalStateException("app.widget.tempos: informe 'hms' ou 'inicio' e 'fim'.");
        }

        public String getFormula() {
            return formula;
        }

        public void setFormula(String formula) {
            this.formula = formula;
        }

        public Integer getMetaSegundos() {
            return metaSegundos;
        }

        public void setMetaSegundos(Integer metaSegundos) {
            this.metaSegundos = metaSegundos;
        }

        public String getHms() {
            return hms;
        }

        public void setHms(String hms) {
            this.hms = hms;
        }

        public String getInicio() {
            return inicio;
        }

        public void setInicio(String inicio) {
            this.inicio = inicio;
        }

        public String getFim() {
            return fim;
        }

        public void setFim(String fim) {
            this.fim = fim;
        }
    }

    /**
     * TMEA — "tempo médio entre atendimentos": how long an atendente went without a call between one
     * and the next. {@code inicio}/{@code fim} are SQL expressions (from application.yml only) giving
     * each call's start/end in epoch seconds; the gap before a call is its {@code inicio} minus the
     * latest {@code fim} of the same atendente's earlier calls that day (overlap = 0). Gaps longer than
     * {@code maxIntervaloMinutos} (lunch, end of shift) are left out. The widget compares it with the
     * average of the other atendentes of the same sector over the last {@code diasReferencia} days.
     */
    public static class Tmea {
        private String inicio;
        private String fim;
        private String formula;
        private int maxIntervaloMinutos = 60;
        private int diasReferencia = 30;
        /** Regra nova (em teste): pausas e sessões de login descontadas do intervalo, sem o corte de 60 min. */
        private Registros pausas = new Registros();
        private Registros sessoes = new Registros();

        public Registros getPausas() {
            return pausas;
        }

        public void setPausas(Registros pausas) {
            this.pausas = pausas;
        }

        public Registros getSessoes() {
            return sessoes;
        }

        public void setSessoes(Registros sessoes) {
            this.sessoes = sessoes;
        }

        public boolean configurado() {
            return inicio != null && !inicio.isBlank() && fim != null && !fim.isBlank();
        }

        public String getInicio() {
            return inicio;
        }

        public void setInicio(String inicio) {
            this.inicio = inicio;
        }

        public String getFim() {
            return fim;
        }

        public void setFim(String fim) {
            this.fim = fim;
        }

        public String getFormula() {
            return formula;
        }

        public void setFormula(String formula) {
            this.formula = formula;
        }

        public int getMaxIntervaloMinutos() {
            return maxIntervaloMinutos;
        }

        public void setMaxIntervaloMinutos(int maxIntervaloMinutos) {
            this.maxIntervaloMinutos = maxIntervaloMinutos;
        }

        public int getDiasReferencia() {
            return diasReferencia;
        }

        public void setDiasReferencia(int diasReferencia) {
            this.diasReferencia = diasReferencia;
        }
    }

    /**
     * Time ranges of an atendente in another table (pauses, login sessions): {@code agente} column with
     * the same names as the calls, {@code inicio}/{@code fim} DATETIME columns ({@code fim} NULL = still
     * open) and an optional extra WHERE condition ({@code filtro}, e.g. {@code evento = 'Pausa'}).
     */
    public static class Registros {
        private String tabela;
        private String agente;
        private String inicio;
        private String fim;
        private String filtro;

        public boolean configurado() {
            return tabela != null && !tabela.isBlank() && agente != null && inicio != null && fim != null;
        }

        public String getTabela() {
            return tabela;
        }

        public void setTabela(String tabela) {
            this.tabela = tabela;
        }

        public String getAgente() {
            return agente;
        }

        public void setAgente(String agente) {
            this.agente = agente;
        }

        public String getInicio() {
            return inicio;
        }

        public void setInicio(String inicio) {
            this.inicio = inicio;
        }

        public String getFim() {
            return fim;
        }

        public void setFim(String fim) {
            this.fim = fim;
        }

        public String getFiltro() {
            return filtro;
        }

        public void setFiltro(String filtro) {
            this.filtro = filtro;
        }
    }

    /**
     * Admin tab "Pausas e comportamentos": {@code pausasSql} is a SELECT returning one row per pause
     * with the columns {@code agente}, {@code ini} and {@code fim} (epoch seconds; fim NULL = not
     * recorded), {@code tipo} and {@code previsto} (expected seconds, NULL when the system has none),
     * with {@code {desde}} standing for the window's start (a DATE expression). {@code curtosCondicao}
     * is a WHERE condition on the calls table marking "atendimento curto encerrado pelo atendente",
     * described to the admin by {@code curtosRotulo}. {@code tipoPrefixo} is a regex stripped from the
     * pause type for display (Matrix: "17295-SEBRATEL-TOALET").
     */
    public static class Comportamento {
        private String pausasSql;
        private String curtosCondicao;
        private String curtosRotulo;
        private String tipoPrefixo;
        /** Optional SELECT (agente, ini epoch, tipo) with the pause types when {@code pausasSql} has none (Native). */
        private String tiposSql;
        /** WHERE condition on the calls: transferred by the atendente. */
        private String transferidasCondicao;
        /** WHERE condition on the calls: call to an internal extension; {@code internasSegundos} = its duration. */
        private String internasCondicao;
        private String internasSegundos;
        /** "Segurar a linha" (calls far above the TMA limit): off where conversations run in parallel (chat). */
        private boolean longasAtivo = true;
        /**
         * The sessions table only gets the row at logoff (Native): the session in progress is estimated
         * from the activity after the last logoff (see {@code TempoLogado}).
         */
        private boolean estimarSessaoAberta;

        public boolean isEstimarSessaoAberta() {
            return estimarSessaoAberta;
        }

        public void setEstimarSessaoAberta(boolean estimarSessaoAberta) {
            this.estimarSessaoAberta = estimarSessaoAberta;
        }

        public boolean isLongasAtivo() {
            return longasAtivo;
        }

        public void setLongasAtivo(boolean longasAtivo) {
            this.longasAtivo = longasAtivo;
        }

        public String getTiposSql() {
            return tiposSql;
        }

        public void setTiposSql(String tiposSql) {
            this.tiposSql = tiposSql;
        }

        public String getTransferidasCondicao() {
            return transferidasCondicao;
        }

        public void setTransferidasCondicao(String transferidasCondicao) {
            this.transferidasCondicao = transferidasCondicao;
        }

        public String getInternasCondicao() {
            return internasCondicao;
        }

        public void setInternasCondicao(String internasCondicao) {
            this.internasCondicao = internasCondicao;
        }

        public String getInternasSegundos() {
            return internasSegundos;
        }

        public void setInternasSegundos(String internasSegundos) {
            this.internasSegundos = internasSegundos;
        }

        public boolean configurado() {
            return pausasSql != null && !pausasSql.isBlank();
        }

        public String getPausasSql() {
            return pausasSql;
        }

        public void setPausasSql(String pausasSql) {
            this.pausasSql = pausasSql;
        }

        public String getCurtosCondicao() {
            return curtosCondicao;
        }

        public void setCurtosCondicao(String curtosCondicao) {
            this.curtosCondicao = curtosCondicao;
        }

        public String getCurtosRotulo() {
            return curtosRotulo;
        }

        public void setCurtosRotulo(String curtosRotulo) {
            this.curtosRotulo = curtosRotulo;
        }

        public String getTipoPrefixo() {
            return tipoPrefixo;
        }

        public void setTipoPrefixo(String tipoPrefixo) {
            this.tipoPrefixo = tipoPrefixo;
        }
    }
}
