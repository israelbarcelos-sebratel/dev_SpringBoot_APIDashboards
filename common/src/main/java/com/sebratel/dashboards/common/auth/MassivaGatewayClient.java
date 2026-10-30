package com.sebratel.dashboards.common.auth;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Consulta de massiva (por telefone ou por CPF/CNPJ) no gateway da Sebratel, em nome da extensão.
 *
 * <p>A extensão não fala com o gateway: o token Google dela só vale aqui (ver {@link GoogleTokenVerifier}) e
 * uma credencial dentro de uma extensão distribuída aos atendentes não seria segredo. O Basic do gateway fica
 * só neste servidor (variáveis MASSIVA_GATEWAY_*).
 */
@Component
public class MassivaGatewayClient {

    private final RestClient http;
    private final boolean configurado;

    public MassivaGatewayClient(RestClient.Builder builder,
                                @Value("${app.massiva.gateway-url:}") String url,
                                @Value("${app.massiva.user:}") String usuario,
                                @Value("${app.massiva.password:}") String senha) {
        this.configurado = !url.isBlank() && !usuario.isBlank() && !senha.isBlank();
        String basic = Base64.getEncoder().encodeToString((usuario + ":" + senha).getBytes(StandardCharsets.UTF_8));
        this.http = builder
                .baseUrl(url.replaceAll("/+$", ""))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .build();
    }

    public boolean configurado() {
        return configurado;
    }

    /** Falha do gateway com o status e a mensagem dele (ex.: 400 "Telefone invalido..."). */
    public static class FalhaGateway extends RuntimeException {
        private final int status;

        public FalhaGateway(int status, String mensagem) {
            super(mensagem);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    /** POST no gateway e devolve o {@code data} da resposta (situação de massiva). */
    public JsonNode consultar(String caminho, Map<String, String> corpo) {
        try {
            JsonNode resposta = http.post()
                    .uri(caminho)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(corpo)
                    .retrieve()
                    .body(JsonNode.class);
            if (resposta == null || !resposta.hasNonNull("data")) {
                throw new FalhaGateway(502, "Resposta inesperada do gateway.");
            }
            return resposta.get("data");
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            String mensagem = status == 400 ? mensagemDe(e) : "Consulta de massiva indisponível no momento.";
            throw new FalhaGateway(status >= 500 || status == 401 || status == 403 ? 502 : status, mensagem);
        } catch (FalhaGateway e) {
            throw e;
        } catch (RuntimeException e) {
            throw new FalhaGateway(502, "Consulta de massiva indisponível no momento.");
        }
    }

    private static String mensagemDe(RestClientResponseException e) {
        try {
            JsonNode json = e.getResponseBodyAs(JsonNode.class);
            if (json != null && json.hasNonNull("message")) {
                return json.get("message").asText();
            }
        } catch (RuntimeException ignorado) {
            // corpo que não é JSON: cai na mensagem genérica
        }
        return "Dados inválidos.";
    }
}
