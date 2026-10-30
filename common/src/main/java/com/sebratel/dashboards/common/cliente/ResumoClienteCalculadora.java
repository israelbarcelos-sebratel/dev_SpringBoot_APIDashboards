package com.sebratel.dashboards.common.cliente;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Resumo de recorrência de um cliente a partir dos protocolos e das notas da pesquisa de satisfação, por regras fixas
 * (sem IA; o texto do motivo vem de palavras-chave, ver {@link TemaAtendimento}).
 *
 * <p><b>Peso do tempo.</b> Cada protocolo pesa {@code 0,5^(idade em dias / 30)}: vale 1 hoje, 0,5 com 30 dias, 0,25 com 60
 * e quase nada depois de um ano. A pontuação é a soma dos pesos, então 5 protocolos em 2 semanas (≈4,2) pesam muito mais
 * que 20 espalhados em 2 anos (≈1,2). Faixas: abaixo de {@value #ATENCAO} sem recorrência relevante; até
 * {@value #RECORRENTE} atenção; até {@value #CRITICO} recorrente; daí em diante crítico.
 */
public final class ResumoClienteCalculadora {

    public static final double ATENCAO = 1.5;
    public static final double RECORRENTE = 3.0;
    public static final double CRITICO = 6.0;
    static final double MEIA_VIDA_DIAS = 30;
    /** Janelas (dias) para dizer "N protocolos nos últimos X dias": a menor com pelo menos 2 vira a frase principal. */
    static final int[] JANELAS = {7, 15, 30, 90, 180, 365};
    static final int MINIMO_NA_JANELA = 2;
    static final int MIN_TECNICOS_VISITA = 3;
    static final String[] NIVEIS = {"sem recorrência relevante", "atenção", "recorrente", "crítico"};

    public record Protocolo(String protocolo, LocalDateTime data, String servico, String tags, String classificacao) {}

    public record Nota(LocalDateTime data, int valor) {}

    private ResumoClienteCalculadora() {}

    public static Map<String, Object> calcular(List<Protocolo> brutos, List<Nota> notas, LocalDateTime agora) {
        List<Item> itens = itens(brutos, agora);
        double pontuacao = itens.stream().mapToDouble(i -> Math.pow(0.5, i.dias / MEIA_VIDA_DIAS)).sum();
        int nivel = pontuacao >= CRITICO ? 3 : pontuacao >= RECORRENTE ? 2 : pontuacao >= ATENCAO ? 1 : 0;

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("nivel", nivel);
        r.put("nivel_rotulo", NIVEIS[nivel]);
        r.put("pontuacao", Math.round(pontuacao * 10) / 10.0);
        Map<String, Integer> contagem = new LinkedHashMap<>();
        for (int j : JANELAS) {
            contagem.put(j + "d", (int) itens.stream().filter(i -> i.dias <= j).count());
        }
        r.put("protocolos", contagem);
        r.put("total_12_meses", itens.size());
        Integer ultimo = itens.isEmpty() ? null : itens.get(0).dias;
        r.put("ultimo_contato_dias", ultimo);

        int janelaPrincipal = janelaPrincipal(itens);
        r.put("janela_principal_dias", janelaPrincipal == 0 ? null : janelaPrincipal);
        Map<TemaAtendimento, Integer> temas = temas(itens, janelaPrincipal == 0 ? 365 : janelaPrincipal);
        List<Map<String, Object>> listaTemas = new ArrayList<>();
        temas.entrySet().stream().sorted(Map.Entry.<TemaAtendimento, Integer>comparingByValue().reversed())
                .forEach(e -> {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("tema", e.getKey().name().toLowerCase(Locale.ROOT));
                    t.put("rotulo", e.getKey().rotulo());
                    t.put("total", e.getValue());
                    listaTemas.add(t);
                });
        r.put("temas", listaTemas);

        Map<String, Object> csat = csat(notas, agora);
        r.put("csat", csat);

        List<String> frases = frases(itens, janelaPrincipal, temas, csat);
        r.put("frases", frases);
        List<String> recomendacoes = recomendacoes(itens, nivel, csat);
        r.put("recomendacoes", recomendacoes);
        List<String> texto = new ArrayList<>(frases);
        texto.addAll(recomendacoes);
        r.put("resumo", String.join(" ", texto));
        return r;
    }

    // ---- itens ----

    private record Item(int dias, List<TemaAtendimento> temas) {}

    private static List<Item> itens(List<Protocolo> brutos, LocalDateTime agora) {
        Set<String> vistos = new LinkedHashSet<>();
        List<Item> itens = new ArrayList<>();
        for (Protocolo p : brutos) {
            if (p.data() == null || (p.protocolo() != null && !vistos.add(p.protocolo()))) {
                continue;
            }
            String texto = TemaAtendimento.normalizar(p.servico(), p.tags(), p.classificacao());
            if (TemaAtendimento.ignorado(texto)) {
                continue;
            }
            int dias = (int) Math.max(0, Duration.between(p.data(), agora).toDays());
            itens.add(new Item(dias, TemaAtendimento.detectar(p.servico(), p.tags(), p.classificacao())));
        }
        itens.sort(Comparator.comparingInt(Item::dias));
        return itens;
    }

    private static int janelaPrincipal(List<Item> itens) {
        for (int j : JANELAS) {
            if (itens.stream().filter(i -> i.dias <= j).count() >= MINIMO_NA_JANELA) {
                return j;
            }
        }
        return 0;
    }

    private static Map<TemaAtendimento, Integer> temas(List<Item> itens, int janela) {
        Map<TemaAtendimento, Integer> m = new EnumMap<>(TemaAtendimento.class);
        for (Item i : itens) {
            if (i.dias <= janela) {
                i.temas.forEach(t -> m.merge(t, 1, Integer::sum));
            }
        }
        return m;
    }

    private static int tecnicosEm(List<Item> itens, int dias) {
        return (int) itens.stream().filter(i -> i.dias <= dias && i.temas.stream().anyMatch(TemaAtendimento::tecnico)).count();
    }

    private static int com(List<Item> itens, int dias, TemaAtendimento tema) {
        return (int) itens.stream().filter(i -> i.dias <= dias && i.temas.contains(tema)).count();
    }

    // ---- CSAT ----

    private static Map<String, Object> csat(List<Nota> notas, LocalDateTime agora) {
        List<Nota> validas = notas.stream().filter(n -> n.valor() >= 1 && n.valor() <= 5 && n.data() != null)
                .sorted(Comparator.comparing(Nota::data).reversed()).toList();
        if (validas.isEmpty()) {
            return null;
        }
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("media", Math.round(validas.stream().mapToInt(Nota::valor).average().orElse(0) * 10) / 10.0);
        c.put("respostas", validas.size());
        c.put("ultima_nota", validas.get(0).valor());
        c.put("ultima_ha_dias", (int) Math.max(0, Duration.between(validas.get(0).data(), agora).toDays()));
        return c;
    }

    // ---- texto ----

    private static String plural(int n, String singular, String plural) {
        return n + " " + (n == 1 ? singular : plural);
    }

    private static String dias(int n) {
        return n == 0 ? "hoje" : n == 1 ? "há 1 dia" : "há " + n + " dias";
    }

    private static List<String> frases(List<Item> itens, int janela, Map<TemaAtendimento, Integer> temas, Map<String, Object> csat) {
        List<String> f = new ArrayList<>();
        if (itens.isEmpty()) {
            f.add("Não há protocolos deste cliente nos últimos 12 meses.");
        } else if (janela == 0) {
            f.add("Este cliente teve " + plural(itens.size(), "protocolo", "protocolos") + " nos últimos 12 meses (o último "
                    + dias(itens.get(0).dias) + "), sem recorrência.");
        } else {
            int n = (int) itens.stream().filter(i -> i.dias <= janela).count();
            StringBuilder s = new StringBuilder("Verifiquei que este cliente já abriu " + n + " protocolos nos últimos "
                    + janela + " dias");
            if (itens.size() > n) {
                s.append(" (").append(itens.size()).append(" em 12 meses)");
            }
            s.append('.');
            f.add(s.toString());
            f.add(temasFrase(temas));
        }
        if (csat != null) {
            double media = (double) csat.get("media");
            int respostas = (int) csat.get("respostas");
            String leitura = media < 3 ? "muito baixa" : media < 4 ? "abaixo do ideal" : "boa";
            f.add("CSAT médio de " + String.format(Locale.forLanguageTag("pt-BR"), "%.1f", media) + " em "
                    + plural(respostas, "resposta", "respostas") + " (" + leitura + ").");
        }
        return f.stream().filter(s -> !s.isBlank()).toList();
    }

    private static String temasFrase(Map<TemaAtendimento, Integer> temas) {
        List<Map.Entry<TemaAtendimento, Integer>> ordem = temas.entrySet().stream()
                .sorted(Map.Entry.<TemaAtendimento, Integer>comparingByValue().reversed()).limit(3).toList();
        if (ordem.isEmpty()) {
            return "";
        }
        List<String> partes = new ArrayList<>();
        for (var e : ordem) {
            partes.add(e.getValue() + " sobre " + e.getKey().rotulo());
        }
        return "Assuntos mais frequentes (presumidos pelas etiquetas): " + String.join(", ", partes) + ".";
    }

    private static List<String> recomendacoes(List<Item> itens, int nivel, Map<String, Object> csat) {
        List<String> r = new ArrayList<>();
        if (tecnicosEm(itens, 30) >= MIN_TECNICOS_VISITA) {
            r.add("Problema técnico recorrente: considere agendar visita técnica.");
        }
        if (com(itens, 30, TemaAtendimento.FINANCEIRO) >= MIN_TECNICOS_VISITA) {
            r.add("Contatos repetidos sobre cobrança: confira boletos e bloqueio antes de seguir.");
        }
        if (com(itens, 90, TemaAtendimento.CANCELAMENTO) >= 1) {
            r.add("Já falou em cancelamento: priorize retenção.");
        }
        if (csat != null && nivel >= 2 && (double) csat.get("media") < 3) {
            r.add("Cliente recorrente e insatisfeito: atenção redobrada.");
        }
        return r;
    }
}
