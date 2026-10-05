package com.sebratel.dashboards.common.auth;

import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code PUT /interno/equipe}: n8n sends, once a day, every row of the support team sheet
 * ({@code n8n/equipe-suporte.json}) and they replace the {@link EquipePlanilha} table. Outside
 * {@code /ext/**} (no Google login): only answers on the internal port, which the stack doesn't publish
 * (see {@link com.sebratel.dashboards.common.web.PortaInterna}).
 *
 * <p>Body: the sheet rows as JSON objects keyed by the header ("Colaborador", "Horários Inicio",
 * "Horário Fim", "Supervisor(a)", "Turno"); headers are matched ignoring accents and case, so small
 * renames don't break it. A body without any valid row is refused: a failed read never wipes the
 * table.
 */
@RestController
public class EquipeController {

    private final EquipePlanilha equipe;

    public EquipeController(EquipePlanilha equipe) {
        this.equipe = equipe;
    }

    @PutMapping("/interno/equipe")
    public Map<String, Object> substituir(@RequestBody List<Map<String, Object>> linhas) {
        Map<String, EquipePlanilha.Pessoa> pessoas = new LinkedHashMap<>(); // nome -> última linha (sem duplicar)
        for (Map<String, Object> linha : linhas) {
            Map<String, String> c = new LinkedHashMap<>();
            linha.forEach((k, v) -> c.put(chave(k), v == null ? null : v.toString().trim()));
            String nome = valor(c, "colaborador");
            if (nome == null) {
                continue;
            }
            nome = nome.replaceAll("\\s+", " ");
            pessoas.put(nome, new EquipePlanilha.Pessoa(nome, valor(c, "horarios inicio", "horario inicio", "inicio"),
                    valor(c, "horario fim", "horarios fim", "fim"), valor(c, "supervisor"), valor(c, "turno")));
        }
        if (pessoas.isEmpty()) {
            throw new AuthException(400, "Nenhuma linha com \"Colaborador\": a tabela atual foi mantida.");
        }
        int n = equipe.substituir(new ArrayList<>(pessoas.values()));
        return Map.of("pessoas", n);
    }

    /** First non-blank column whose (normalized) header starts with one of the prefixes. */
    private static String valor(Map<String, String> c, String... prefixos) {
        for (String p : prefixos) {
            for (Map.Entry<String, String> e : c.entrySet()) {
                if (e.getKey().startsWith(p) && e.getValue() != null && !e.getValue().isBlank()) {
                    return e.getValue().length() > 255 ? e.getValue().substring(0, 255) : e.getValue();
                }
            }
        }
        return null;
    }

    private static String chave(String k) {
        return Normalizer.normalize(k, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", "").trim().replaceAll("\\s+", " ");
    }
}
