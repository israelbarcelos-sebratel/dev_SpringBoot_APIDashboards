package com.sebratel.dashboards.common.cliente;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Ligações do cliente na Native para a extensão. Fica em /ext/**, atrás do
 * {@link com.sebratel.dashboards.common.auth.ExtAuthInterceptor}: só e-mail @sebratel.com.br verificado.
 */
@RestController
public class ClienteLigacoesController {

    private final ObjectProvider<ClienteLigacoesService> servico;

    public ClienteLigacoesController(ObjectProvider<ClienteLigacoesService> servico) {
        this.servico = servico;
    }

    @PostMapping("/ext/cliente/ligacoes")
    public ResponseEntity<?> ligacoes(@RequestBody Map<String, String> corpo) {
        ClienteLigacoesService s = servico.getIfAvailable();
        if (s == null) {
            return ResponseEntity.status(503).body(Map.of("error", "Ligações do cliente não estão disponíveis neste servidor."));
        }
        Map<String, Object> r = s.resumir(corpo == null ? null : corpo.get("documento"), corpo == null ? null : corpo.get("telefone"));
        if (r == null) {
            return ResponseEntity.ok(Map.of("sem_telefone", true));
        }
        return ResponseEntity.ok(r);
    }
}
