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
    }
}
