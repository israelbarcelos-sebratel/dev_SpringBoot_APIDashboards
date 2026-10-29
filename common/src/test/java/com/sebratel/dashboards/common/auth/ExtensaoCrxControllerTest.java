package com.sebratel.dashboards.common.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CRX served from the real package committed in {@code extension/} (the one the Dockerfile ships). */
class ExtensaoCrxControllerTest {

    private static final Path CRX = Path.of("../extension/tma-tme.crx");
    private static final Path MANIFEST = Path.of("../extension/tma-tme/manifest.json");

    private static MockMvc mvc(String crx, String baseUrl) {
        return MockMvcBuilders.standaloneSetup(new ExtensaoCrxController(crx, baseUrl)).build();
    }

    @Test
    void crxEhServidoComTipoDeExtensaoEBytesIntactos() throws Exception {
        byte[] corpo = mvc(CRX.toString(), "").perform(get("/download/tma-tme.crx"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/x-chrome-extension"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(Files.readAllBytes(CRX), corpo);
    }

    @Test
    void xmlApontaParaOCrxComVersaoEIdDoProprioPacote() throws Exception {
        JsonNode manifest = new ObjectMapper().readTree(Files.readString(MANIFEST));
        String id = CrxPacote.idDaChave(Base64.getDecoder().decode(manifest.get("key").asText()));
        String versao = manifest.get("version").asText();

        mvc(CRX.toString(), "http://10.0.0.5:8092/").perform(get("/download/updates.xml"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/xml;charset=UTF-8"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("appid='" + id + "'")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "codebase='http://10.0.0.5:8092/download/tma-tme.crx' version='" + versao + "'")));
    }

    @Test
    void semBaseUrlUsaOrigemDaRequisicao() throws Exception {
        mvc(CRX.toString(), "").perform(get("/download/updates.xml").header("Host", "ext.exemplo.local:8092"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "codebase='http://ext.exemplo.local:8092/download/tma-tme.crx'")));
    }

    @Test
    void origemComCaracteresDeXmlEhEscapada() throws Exception {
        mvc(CRX.toString(), "").perform(get("/download/updates.xml").header("Host", "a'b<c.local"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("a'b<c"))));
    }

    @Test
    void crxAusenteDa404SemVazarCaminho() throws Exception {
        MockMvc ausente = mvc("/nao/existe.crx", "");
        ausente.perform(get("/download/tma-tme.crx")).andExpect(status().isNotFound());
        ausente.perform(get("/download/updates.xml")).andExpect(status().isNotFound());
    }

    @Test
    void arquivoQueNaoEhCrxDa404() throws Exception {
        Path lixo = Files.createTempFile("falso", ".crx");
        try {
            Files.writeString(lixo, "isto não é um crx");
            mvc(lixo.toString(), "").perform(get("/download/tma-tme.crx")).andExpect(status().isNotFound());
        } finally {
            Files.deleteIfExists(lixo);
        }
    }

    @Test
    void baseUrlInvalidaImpedeOStartup() {
        assertThrows(IllegalStateException.class, () -> new ExtensaoCrxController(CRX.toString(), "javascript:alert(1)"));
        assertThrows(IllegalStateException.class, () -> new ExtensaoCrxController(CRX.toString(), "http://a'b"));
    }

    @Test
    void versaoDoCrxCommitadoBateComAPastaDaExtensao() throws Exception {
        String daPasta = new ObjectMapper().readTree(Files.readString(MANIFEST)).get("version").asText();
        assertEquals(daPasta, CrxPacote.ler(Files.readAllBytes(CRX)).versao(),
                "extension/tma-tme.crx está desatualizado: reempacote a extensão com a chave original");
    }
}
