package com.sebratel.dashboards.common.cliente;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Resumo de recorrência do cliente (protocolos, assuntos presumidos e CSAT) para a extensão. Fica em /ext/**, atrás do
 * {@link com.sebratel.dashboards.common.auth.ExtAuthInterceptor}: só e-mail @sebratel.com.br verificado.
 */
@RestController
public class ClienteResumoController {

    private final ObjectProvider<ClienteResumoService> servico;

    public ClienteResumoController(ObjectProvider<ClienteResumoService> servico) {
        this.servico = servico;
    }

    @PostMapping("/ext/cliente/resumo")
    public ResponseEntity<?> resumo(@RequestBody Map<String, String> corpo) {
        ClienteResumoService s = servico.getIfAvailable();
        if (s == null) {
            return ResponseEntity.status(503).body(Map.of("error", "Resumo do cliente não está disponível neste servidor."));
        }
        Map<String, Object> r = s.resumir(corpo == null ? null : corpo.get("documento"), corpo == null ? null : corpo.get("telefone"));
        if (r == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Informe o documento (CPF/CNPJ) ou o telefone."));
        }
        return ResponseEntity.ok(r);
    }
}
