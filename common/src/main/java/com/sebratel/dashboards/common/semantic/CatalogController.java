package com.sebratel.dashboards.common.semantic;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Discovery endpoint: the plain-language map an AI reads before building a dashboard. */
@RestController
public class CatalogController {

    private final SemanticService semantic;

    public CatalogController(SemanticService semantic) {
        this.semantic = semantic;
    }

    @GetMapping("/dominios")
    public DomainCatalog dominios() {
        return semantic.catalogo();
    }
}
