package com.sebratel.dashboards.common.cliente;

import com.fasterxml.jackson.databind.JsonNode;
import com.sebratel.dashboards.common.auth.MassivaGatewayClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Quem é o cliente: junta o que o atendente informou (documento e/ou telefone) com o cadastro do ERP, que o
 * MassivasService devolve no {@code perfil} da consulta de massiva (todos os telefones do cadastro, já num formato
 * único). É daí que protocolos da Matrix e ligações da Native são achados pelo mesmo cliente, mesmo que ele tenha
 * ligado de outro número.
 */
@Component
public class PerfilClienteFonte {

    private static final Logger log = LoggerFactory.getLogger(PerfilClienteFonte.class);

    /**
     * @param documentos formas do CPF/CNPJ como cada tabela guarda (com e sem zeros à esquerda)
     * @param telefones  telefones canônicos (DDD + número) do cliente: o informado e os do cadastro
     * @param perfil     o {@code perfil} do MassivasService; null se o cadastro não foi consultado
     */
    public record Identidade(Set<String> documentos, Set<String> telefones, JsonNode perfil) {
        public boolean vazia() {
            return documentos.isEmpty() && telefones.isEmpty();
        }
    }

    private final MassivaGatewayClient gateway;

    public PerfilClienteFonte(MassivaGatewayClient gateway) {
        this.gateway = gateway;
    }

    public Identidade resolver(String documento, String telefone) {
        Set<String> docs = ClienteResumoService.variantesDocumento(documento);
        Set<String> fones = new LinkedHashSet<>();
        canonico(telefone, fones);
        JsonNode perfil = null;
        if (gateway.configurado() && (!docs.isEmpty() || !fones.isEmpty())) {
            try {
                boolean porDocumento = documento != null && !docs.isEmpty();
                JsonNode data = gateway.consultar(porDocumento ? "/api/v1/matrix/documento" : "/api/v1/matrix/telefone",
                        porDocumento ? Map.of("documento", documento) : Map.of("telefone", telefone));
                perfil = data.get("perfil");
                boolean compartilhado = "telefone_compartilhado".equals(data.path("status").asText());
                // Por telefone, só um cadastro identifica o cliente; por documento, vários (matriz e filiais) são o mesmo.
                boolean ambiguo = !porDocumento && perfil != null && perfil.path("registros_people").asInt(0) > 1;
                if (compartilhado) {
                    fones.clear(); // telefone da própria empresa ou de milhares de cadastros: não identifica ninguém
                } else if (!ambiguo && perfil != null && perfil.has("telefones")) {
                    perfil.get("telefones").forEach(t -> canonico(t.asText(), fones));
                }
            } catch (RuntimeException e) {
                log.warn("Cadastro do cliente indisponível ({}); seguindo só com o que foi informado", e.getClass().getSimpleName());
            }
        }
        return new Identidade(docs, fones, perfil);
    }

    /** DDD + número (10 ou 11 dígitos); descarta ramal, "anonymous" e número sem DDD. */
    static void canonico(String bruto, Set<String> destino) {
        String d = bruto == null ? "" : bruto.replaceAll("\\D", "");
        if (d.length() > 10 && d.startsWith("0")) {
            d = d.substring(1);
        }
        if (d.length() > 11 && d.startsWith("55")) {
            d = d.substring(2);
        }
        if ((d.length() == 10 || d.length() == 11) && d.charAt(0) != '0') {
            destino.add(d);
        }
    }
}
