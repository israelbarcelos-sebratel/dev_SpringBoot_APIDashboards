package com.sebratel.dashboards.common.cliente;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * Assunto presumido de um protocolo, a partir de palavras-chave nas etiquetas, no serviço e na classificação do
 * atendimento (a Matrix não grava o texto da conversa). Um protocolo pode ter mais de um assunto.
 *
 * <p>As palavras vêm do vocabulário de reclamações de provedor de banda larga (Anatel/Procon: qualidade e
 * funcionamento, cobrança, cancelamento, instalação) e das etiquetas que a própria operação já usa
 * ({@code ISA_CLIENTE_SEM_CONEXÃO}, {@code ISA_SEGUNDA_VIA_BOLETOS}…). Palavra terminada em {@code *} vale como
 * prefixo ({@code lent*} casa lenta, lentidão, lento); as demais só casam a palavra inteira ({@code los} não casa
 * "solos").
 */
public enum TemaAtendimento {
    SEM_CONEXAO("sem conexão", true,
            "sem conexao", "sem internet", "sem acesso", "sem sinal", "falha na conexao", "offline", "los",
            "internet caiu", "fibra rompida", "rompimento", "sem navegar", "nao navega", "nao conecta"),
    LENTIDAO("lentidão", true,
            "lent*", "velocidade", "latencia", "ping", "travando", "travamento", "baixa velocidade"),
    INSTABILIDADE("instabilidade/quedas", true,
            "intermitente*", "intermitencia", "oscila*", "instavel", "instabilidade", "queda*", "caindo", "cai e volta",
            "perda de pacote"),
    EQUIPAMENTO("equipamento/Wi-Fi", true,
            "wifi", "wi fi", "roteador", "onu", "ont", "modem", "equipamento*", "credenciais", "cabo", "sinal fraco",
            "alcance"),
    INSTALACAO("instalação/visita", false,
            "instalacao", "reagendamento", "agendamento*", "agendado", "visita*",
            "ordem de servico", "retorno instalacao", "retirada equipamento"),
    FINANCEIRO("cobrança/financeiro", false,
            "boleto*", "segunda via", "fatura*", "cobranca", "divida*", "negociar", "desbloqueio", "comprovante",
            "vencimento", "financeiro", "pagamento*", "confianca", "debito"),
    CADASTRO("cadastro/mudança de plano", false,
            "troca de endereco", "mudanca de endereco", "titularidade", "dados cadastrais", "migracao", "troca de plano",
            "upgrade", "downgrade"),
    CANCELAMENTO("cancelamento", false, "cancel*", "retido", "retencao", "rescisao", "multa"),
    COMERCIAL("comercial/vendas", false, "vendas", "venda concluida", "planos", "contratar", "contratacao"),
    /** Só quando nada mais específico bate: atendimento de suporte sem etiqueta do problema. */
    SUPORTE("suporte técnico", true, "suporte*", "chamado*");

    /** Texto que indica contato que não é do cliente (campanha, RH): não entra na contagem. */
    private static final List<String> IGNORADOS = List.of(
            "marketing", "hsm", "caoc", "departamento pessoal", "fluxo dp", "agente dp", "notification",
            "collection automation", "recepcao");

    private final String rotulo;
    private final boolean tecnico;
    private final List<String> palavras;

    TemaAtendimento(String rotulo, boolean tecnico, String... palavras) {
        this.rotulo = rotulo;
        this.tecnico = tecnico;
        this.palavras = List.of(palavras);
    }

    public String rotulo() {
        return rotulo;
    }

    /** Problema de conexão/equipamento: sustenta a sugestão de visita técnica. */
    public boolean tecnico() {
        return tecnico;
    }

    boolean casa(String textoNormalizado) {
        for (String p : palavras) {
            boolean prefixo = p.endsWith("*");
            String chave = prefixo ? p.substring(0, p.length() - 1) : p;
            if (textoNormalizado.contains(" " + chave + (prefixo ? "" : " "))) {
                return true;
            }
        }
        return false;
    }

    /** Minúsculas, sem acento e com tudo que não é letra/número virando espaço, entre espaços: " isa sem conexao ". */
    static String normalizar(String... partes) {
        StringBuilder sb = new StringBuilder(" ");
        for (String parte : partes) {
            if (parte != null) {
                sb.append(parte).append(' ');
            }
        }
        String sem = Normalizer.normalize(sb.toString(), Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return " " + sem.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim() + " ";
    }

    static boolean ignorado(String textoNormalizado) {
        return IGNORADOS.stream().anyMatch(i -> textoNormalizado.contains(" " + i));
    }

    /** Assuntos de um protocolo, na ordem do enum; vazio se não bate nenhuma palavra. */
    public static List<TemaAtendimento> detectar(String... partes) {
        String texto = normalizar(partes);
        if (ignorado(texto)) {
            return List.of();
        }
        List<TemaAtendimento> temas = java.util.Arrays.stream(values())
                .filter(t -> t != SUPORTE && t.casa(texto)).toList();
        return temas.isEmpty() && SUPORTE.casa(texto) ? List.of(SUPORTE) : temas;
    }
}
