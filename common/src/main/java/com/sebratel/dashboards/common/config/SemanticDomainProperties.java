package com.sebratel.dashboards.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps each business domain (atendimentos, satisfacao, …) to the underlying data set and the
 * columns that stand for its dimensions — the ONLY place in the semantic layer that knows internal
 * names. Bound from {@code app.domains} in each service's application.yml, so matrix-api and
 * native-api point the same domains at their own data sets without any code change.
 *
 * <pre>
 * app:
 *   domains:
 *     atendimentos:
 *       titulo: Atendimentos
 *       descricao: Conversas e chamados recebidos pela operação.
 *       tabela: db_matrix
 *       unidade: atendimentos
 *       dimensoes: { canal: canal, servico: servico, atendente: atendente }
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "app")
public class SemanticDomainProperties {

    private Map<String, Domain> domains = new LinkedHashMap<>();

    public Map<String, Domain> getDomains() {
        return domains;
    }

    public void setDomains(Map<String, Domain> domains) {
        this.domains = domains;
    }

    /** A configured domain, or null when this service does not expose it. */
    public Domain domain(String nome) {
        return domains.get(nome);
    }

    public static class Domain {
        private String titulo;
        private String descricao;
        private String tabela;
        private String unidade = "registros";
        /** Numeric score column (e.g. satisfaction 1..5); enables the satisfaction-style resumo. */
        private String notaColumn;
        /** Semantic dimension name -> real column name (canal -> canal, motivo -> nom_motivo, …). */
        private Map<String, String> dimensoes = new LinkedHashMap<>();
        /**
         * Ordered chart label -> duration column for {@code /tempos} (queue wait, response time…).
         * Kept apart from {@link #dimensoes} because these are "HH:MM:SS" duration columns aggregated
         * into a mean, not categorical breakdowns — and the columns differ per service (matrix:
         * tempo_fila/tmic/tmia; native: espera/atendimento).
         */
        private Map<String, String> tempos = new LinkedHashMap<>();
        /**
         * Where the {@code /duracao} histogram reads its duration from, or null when the domain
         * doesn't expose that route. Kept apart from {@link #dimensoes} and {@link #tempos} because
         * the underlying representation differs per service — matrix stores whole seconds
         * ({@code segundos: seg_pausado}), native pausas an "HH:MM:SS" varchar ({@code hms:
         * tempo_em_pausa}) and native jornada only start/end datetimes ({@code inicio:}/{@code fim:}) —
         * yet all three yield the same seconds histogram. See {@link com.sebratel.dashboards.common.web.DurationSource}.
         */
        private Duracao duracao;

        public String getTitulo() {
            return titulo;
        }

        public void setTitulo(String titulo) {
            this.titulo = titulo;
        }

        public String getDescricao() {
            return descricao;
        }

        public void setDescricao(String descricao) {
            this.descricao = descricao;
        }

        public String getTabela() {
            return tabela;
        }

        public void setTabela(String tabela) {
            this.tabela = tabela;
        }

        public String getUnidade() {
            return unidade;
        }

        public void setUnidade(String unidade) {
            this.unidade = unidade;
        }

        public String getNotaColumn() {
            return notaColumn;
        }

        public void setNotaColumn(String notaColumn) {
            this.notaColumn = notaColumn;
        }

        public Map<String, String> getDimensoes() {
            return dimensoes;
        }

        public void setDimensoes(Map<String, String> dimensoes) {
            this.dimensoes = dimensoes;
        }

        public Map<String, String> getTempos() {
            return tempos;
        }

        public void setTempos(Map<String, String> tempos) {
            this.tempos = tempos;
        }

        public Duracao getDuracao() {
            return duracao;
        }

        public void setDuracao(Duracao duracao) {
            this.duracao = duracao;
        }
    }

    /**
     * Duration source for a domain's {@code /duracao} histogram. Exactly one representation is set:
     * {@code segundos} (a whole-seconds column), {@code hms} (an "HH:MM:SS" varchar) or the
     * {@code inicio}/{@code fim} pair (derive from two datetimes). {@code unidade} labels the axis
     * (always seconds today, but kept configurable alongside the other domain metadata).
     */
    public static class Duracao {
        private String segundos;
        private String hms;
        private String inicio;
        private String fim;
        private String unidade = "segundos";

        public String getSegundos() {
            return segundos;
        }

        public void setSegundos(String segundos) {
            this.segundos = segundos;
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

        public String getUnidade() {
            return unidade;
        }

        public void setUnidade(String unidade) {
            this.unidade = unidade;
        }
    }
}
