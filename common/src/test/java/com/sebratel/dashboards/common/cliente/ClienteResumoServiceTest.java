package com.sebratel.dashboards.common.cliente;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClienteResumoServiceTest {

    @Test
    void documentoComEsemZerosAEsquerda() {
        assertEquals(List.of("2046186060", "02046186060"), List.copyOf(ClienteResumoService.variantesDocumento("020.461.860-60")));
        assertEquals(List.of("13019863000152"), List.copyOf(ClienteResumoService.variantesDocumento("13.019.863/0001-52")));
        assertTrue(ClienteResumoService.variantesDocumento("123").isEmpty());
        assertTrue(ClienteResumoService.variantesDocumento(null).isEmpty());
    }

    @Test
    void telefoneComESem55() {
        assertEquals(List.of("51991357457", "5551991357457"), List.copyOf(ClienteResumoService.variantesTelefone("5551991357457")));
        assertEquals(List.of("51991357457", "5551991357457"), List.copyOf(ClienteResumoService.variantesTelefone("(51) 99135-7457")));
        assertTrue(ClienteResumoService.variantesTelefone("1234").isEmpty());
    }
}
