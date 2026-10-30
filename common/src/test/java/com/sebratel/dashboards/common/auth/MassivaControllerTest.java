package com.sebratel.dashboards.common.auth;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MassivaControllerTest {

    private static final String BASIC_ADMIN = "Basic YWRtaW46c2VuaGE=";

    private MockRestServiceServer servidor;

    private MockMvc mvc(String url, String usuario, String senha) {
        RestClient.Builder builder = RestClient.builder();
        servidor = MockRestServiceServer.bindTo(builder).build();
        return MockMvcBuilders.standaloneSetup(new MassivaController(
                new MassivaGatewayClient(builder, url, usuario, senha))).build();
    }

    @Test
    void telefoneVaiAoGatewayComBasicEDevolveOData() throws Exception {
        MockMvc mvc = mvc("https://gw.exemplo/", "admin", "senha");
        servidor.expect(requestTo("https://gw.exemplo/api/v1/matrix/telefone"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", BASIC_ADMIN))
                .andExpect(content().json("{\"telefone\":\"51995664811\"}"))
                .andRespond(withSuccess("{\"success\":true,\"data\":{\"status\":\"em_massiva\",\"em_massiva\":true,"
                        + "\"motivo\":\"Rompimento de fibra\"}}", MediaType.APPLICATION_JSON));

        mvc.perform(post("/ext/massiva/telefone").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"telefone\":\"51995664811\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("em_massiva"))
                .andExpect(jsonPath("$.motivo").value("Rompimento de fibra"));
        servidor.verify();
    }

    @Test
    void documentoVaiAoCaminhoDeDocumento() throws Exception {
        MockMvc mvc = mvc("https://gw.exemplo", "admin", "senha");
        servidor.expect(requestTo("https://gw.exemplo/api/v1/matrix/documento"))
                .andExpect(content().json("{\"documento\":\"123.456.789-01\"}"))
                .andRespond(withSuccess("{\"data\":{\"status\":\"sem_massiva\"}}", MediaType.APPLICATION_JSON));

        mvc.perform(post("/ext/massiva/documento").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documento\":\"123.456.789-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("sem_massiva"));
        servidor.verify();
    }

    @Test
    void entradaInvalidaDoGatewayVoltaComAMensagemDele() throws Exception {
        MockMvc mvc = mvc("https://gw.exemplo", "admin", "senha");
        servidor.expect(requestTo("https://gw.exemplo/api/v1/matrix/telefone"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"Telefone invalido: informe DDD + numero.\"}"));

        mvc.perform(post("/ext/massiva/telefone").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"telefone\":\"123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Telefone invalido: informe DDD + numero."));
    }

    @Test
    void erroDoGatewayViraBadGatewaySemVazarDetalhes() throws Exception {
        MockMvc mvc = mvc("https://gw.exemplo", "admin", "senha");
        servidor.expect(requestTo("https://gw.exemplo/api/v1/matrix/telefone"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED));

        mvc.perform(post("/ext/massiva/telefone").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"telefone\":\"51995664811\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("Consulta de massiva indisponível no momento."));
    }

    @Test
    void semConfiguracaoResponde503SemChamarOGateway() throws Exception {
        MockMvc mvc = mvc("", "", "");

        mvc.perform(post("/ext/massiva/documento").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documento\":\"12345678901\"}"))
                .andExpect(status().isServiceUnavailable());
        servidor.verify();
    }

    @Test
    void corpoSemOCampoResponde400() throws Exception {
        MockMvc mvc = mvc("https://gw.exemplo", "admin", "senha");

        mvc.perform(post("/ext/massiva/telefone").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Informe o telefone."));
    }
}
