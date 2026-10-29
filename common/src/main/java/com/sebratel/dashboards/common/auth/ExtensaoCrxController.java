package com.sebratel.dashboards.common.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Self-hosted install of the TMA/TME extension by Chrome policy (Google Admin console "add by URL",
 * force-install): {@code GET /download/updates.xml} is the update manifest and
 * {@code GET /download/tma-tme.crx} the signed package it points to. Unlike the zip served by
 * {@link ExtensaoDownloadController} (manual "load unpacked"), this path gets automatic install and
 * updates on managed browsers.
 *
 * <p>Public on purpose, like the rest of {@code /download}: Chrome fetches both without any login, and the
 * package holds nothing secret (the private signing key never leaves the developer's machine; Chrome
 * verifies the signature against the ID). Version and ID in the XML come from the CRX itself
 * ({@link CrxPacote}). The URL written into the XML comes from {@code app.extensao.base-url}; set it in
 * every shared environment, since the fallback (the request's own scheme/host) trusts the Host header.
 */
@RestController
public class ExtensaoCrxController {

    private static final Logger log = LoggerFactory.getLogger(ExtensaoCrxController.class);
    private static final String TIPO_CRX = "application/x-chrome-extension";
    private static final String TIPO_XML = "application/xml;charset=UTF-8";

    private final Path crx;
    private final String baseUrl;
    private volatile Lido cache;

    private record Lido(long modificadoEm, CrxPacote pacote) {
    }

    public ExtensaoCrxController(@Value("${app.extensao.crx:/app/extension-crx/tma-tme.crx}") String crx,
                                 @Value("${app.extensao.base-url:}") String baseUrl) {
        this.crx = Path.of(crx);
        this.baseUrl = baseUrl.strip().replaceAll("/+$", "");
        if (!this.baseUrl.isEmpty() && !this.baseUrl.matches("https?://[^\\s'\"<>&]+")) {
            throw new IllegalStateException("app.extensao.base-url deve ser http(s)://host[:porta][/caminho]");
        }
    }

    @GetMapping(value = "/download/tma-tme.crx", produces = TIPO_CRX)
    public ResponseEntity<byte[]> pacote() {
        return pacoteAtual()
                .map(p -> ResponseEntity.ok()
                        .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                        .header("X-Content-Type-Options", "nosniff")
                        .body(p.bytes()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/download/updates.xml", produces = TIPO_XML)
    public ResponseEntity<String> atualizacoes(HttpServletRequest requisicao) {
        return pacoteAtual()
                .map(p -> ResponseEntity.ok()
                        .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                        .header("X-Content-Type-Options", "nosniff")
                        .body(xml(p, origem(requisicao))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private String origem(HttpServletRequest requisicao) {
        if (!baseUrl.isEmpty()) {
            return baseUrl;
        }
        return ServletUriComponentsBuilder.fromContextPath(requisicao).build().toUriString();
    }

    private static String xml(CrxPacote p, String origem) {
        String codebase = HtmlUtils.htmlEscape(origem + "/download/tma-tme.crx");
        return """
                <?xml version='1.0' encoding='UTF-8'?>
                <gupdate xmlns='http://www.google.com/update2/response' protocol='2.0'>
                  <app appid='%s'>
                    <updatecheck codebase='%s' version='%s' />
                  </app>
                </gupdate>
                """.formatted(p.id(), codebase, p.versao());
    }

    /** CRX re-read only when the file changes (a redeploy); empty when it isn't on the server or is invalid. */
    private Optional<CrxPacote> pacoteAtual() {
        try {
            if (!Files.isRegularFile(crx)) {
                return Optional.empty();
            }
            long modificado = Files.getLastModifiedTime(crx).toMillis();
            Lido atual = cache;
            if (atual == null || atual.modificadoEm() != modificado) {
                atual = new Lido(modificado, CrxPacote.ler(Files.readAllBytes(crx)));
                cache = atual;
            }
            return Optional.of(atual.pacote());
        } catch (IOException | IllegalArgumentException e) {
            log.error("CRX da extensão inválido ou ilegível em {}: {}", crx, e.getMessage());
            return Optional.empty();
        }
    }
}
