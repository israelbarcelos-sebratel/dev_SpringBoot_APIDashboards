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
}
