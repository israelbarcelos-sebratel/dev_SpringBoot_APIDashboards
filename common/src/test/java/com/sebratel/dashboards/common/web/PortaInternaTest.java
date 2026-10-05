package com.sebratel.dashboards.common.web;

import com.sebratel.dashboards.common.auth.EquipeController;
import com.sebratel.dashboards.common.auth.EquipePlanilha;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PortaInternaTest {

    private MockMvc mvc(int porta) {
        EquipePlanilha equipe = mock(EquipePlanilha.class);
        when(equipe.substituir(anyList())).thenReturn(1);
        return MockMvcBuilders.standaloneSetup(new EquipeController(equipe))
                .addMappedInterceptors(new String[]{"/interno/**"}, new PortaInterna(porta).filtro()).build();
    }

    private static MockHttpServletRequestBuilder enviar(int portaLocal) {
        return put("/interno/equipe").contentType(MediaType.APPLICATION_JSON)
                .content("[{\"Colaborador\":\"Fulano de Tal\",\"Turno\":\"Manhã\"}]")
                .with(r -> {
                    r.setLocalPort(portaLocal);
                    return r;
                });
    }

    @Test
    void soNaPortaInterna() throws Exception {
        mvc(8093).perform(enviar(8093)).andExpect(status().isOk());
        mvc(8093).perform(enviar(8092)).andExpect(status().isNotFound());
    }

    @Test
    void semPortaInternaNuncaResponde() throws Exception {
        mvc(0).perform(enviar(0)).andExpect(status().isNotFound());
        mvc(0).perform(enviar(8092)).andExpect(status().isNotFound());
    }
}
