package com.sebratel.dashboards.common.auth;

import com.sebratel.dashboards.common.auth.CorrespondenciaNomes.Par;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Admin screen "Relacionar nomes Matrix ↔ Native" of the Chrome extension ({@code correspondencias.html}):
 * lists the pairs {@link CorrespondenciaNomes} found and lets an admin link, unlink or restore them.
 * Both APIs serve it (the extension uses Native) and share the adjustments through app-db.
 */
@RestController
public class CorrespondenciaController {

    private final UsuarioRepository usuarios;
    private final CorrespondenciaNomes correspondencia;

    public CorrespondenciaController(UsuarioRepository usuarios, CorrespondenciaNomes correspondencia) {
        this.usuarios = usuarios;
        this.correspondencia = correspondencia;
    }

    /**
     * {@code pares} (matrix, nativo, origem, setorDiferente), the names of each system without any pair
     * in effect ({@code semParMatrix}/{@code semParNative}), and every name of each system (last 90 days)
     * for the "vincular" pickers.
     */
    @GetMapping("/ext/correspondencias")
    public Map<String, Object> listar(@RequestAttribute(ExtAuthInterceptor.EMAIL_ATTR) String email) {
        requireAdmin(email);
        List<Par> pares = correspondencia.pares();
        Set<String> comParMatrix = new HashSet<>();
        Set<String> comParNative = new HashSet<>();
        for (Par p : pares) {
            if (!CorrespondenciaNomes.BLOQUEADO.equals(p.origem())) {
                comParMatrix.add(p.matrix());
                comParNative.add(p.nativo());
            }
        }
        List<String> matrix = ordenar(correspondencia.nomesMatrix());
        List<String> nativo = ordenar(correspondencia.nomesNative());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("pares", pares);
        resp.put("semParMatrix", matrix.stream().filter(n -> !comParMatrix.contains(n)).toList());
        resp.put("semParNative", nativo.stream().filter(n -> !comParNative.contains(n)).toList());
        resp.put("nomesMatrix", matrix);
        resp.put("nomesNative", nativo);
        return resp;
    }

    /** Body {@code {matrix, nativo, acao}}: acao = "vincular" | "desvincular" | "restaurar". */
    @PutMapping("/ext/correspondencias")
    public Map<String, Object> ajustar(@RequestAttribute(ExtAuthInterceptor.EMAIL_ATTR) String email,
                                       @RequestBody Map<String, String> body) {
        requireAdmin(email);
        String matrix = body.get("matrix");
        String nativo = body.get("nativo");
        switch (String.valueOf(body.get("acao"))) {
            case "vincular" -> correspondencia.vincular(matrix, nativo, email);
            case "desvincular" -> correspondencia.desvincular(matrix, nativo, email);
            case "restaurar" -> correspondencia.restaurar(matrix, nativo);
            default -> throw new IllegalArgumentException("Ação inválida.");
        }
        return listar(email);
    }

    private static List<String> ordenar(List<String> nomes) {
        return nomes.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private void requireAdmin(String email) {
        if (!UsuarioRepository.ADMIN.equals(usuarios.find(email).role())) {
            throw new AuthException(403, "Somente administradores.");
        }
    }
}
