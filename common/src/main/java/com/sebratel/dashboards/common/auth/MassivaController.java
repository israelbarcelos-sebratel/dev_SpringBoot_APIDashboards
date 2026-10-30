package com.sebratel.dashboards.common.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.sebratel.dashboards.common.auth.MassivaGatewayClient.FalhaGateway;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Massiva de quem ligou (telefone) ou de um CPF/CNPJ digitado pelo atendente, para a extensão. Fica atrás do
 * {@link ExtAuthInterceptor} (/ext/**): só e-mail @sebratel.com.br verificado. A resposta é a do gateway
 * (status, motivo, previsão), já sem nome nem endereço do cliente.
 */
@RestController
public class MassivaController {

    private final MassivaGatewayClient gateway;

    public MassivaController(MassivaGatewayClient gateway) {
        this.gateway = gateway;
    }

    @PostMapping("/ext/massiva/telefone")
    public ResponseEntity<?> porTelefone(@RequestBody Map<String, String> corpo) {
        return consultar("/api/v1/matrix/telefone", "telefone", corpo);
    }

    @PostMapping("/ext/massiva/documento")
    public ResponseEntity<?> porDocumento(@RequestBody Map<String, String> corpo) {
        return consultar("/api/v1/matrix/documento", "documento", corpo);
    }

    private ResponseEntity<?> consultar(String caminho, String campo, Map<String, String> corpo) {
        String valor = corpo == null ? null : corpo.get(campo);
        if (valor == null || valor.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Informe o " + campo + "."));
        }
        if (!gateway.configurado()) {
            return ResponseEntity.status(503).body(Map.of("error", "Consulta de massiva não configurada neste servidor."));
        }
        try {
            JsonNode data = gateway.consultar(caminho, Map.of(campo, valor));
            return ResponseEntity.ok(data);
        } catch (FalhaGateway e) {
            return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
        }
    }
}
