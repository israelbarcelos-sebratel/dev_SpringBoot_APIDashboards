package com.sebratel.dashboards.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
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
 *       tme: { hms: espera }                                       # "HH:MM:SS" varchar
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "app.widget")
public class WidgetProperties {

    private String dataColuna;
    private Map<String, Metrica> tempos = new LinkedHashMap<>();

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

    /** Either {@code hms} (an "HH:MM:SS" column) or {@code inicio}+{@code fim} (two datetime columns). */
    public static class Metrica {
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
}
