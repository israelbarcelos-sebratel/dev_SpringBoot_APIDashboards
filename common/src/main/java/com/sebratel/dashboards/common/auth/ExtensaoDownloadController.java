package com.sebratel.dashboards.common.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Public distribution of the TMA/TME Chrome extension to colleagues: {@code GET /download} is an
 * install page (Sebratel design system) and {@code GET /download/tma-tme-extensao.zip} the package,
 * zipped on the fly from {@code app.extensao.dir} (the repo's {@code extension/tma-tme}, copied into
 * the image by the Dockerfile).
 *
 * <p>Chrome no longer installs .crx files from websites, so the page walks through "Carregar sem
 * compactação" (developer mode). The manifest carries the extension's public key, so every install
 * gets the same ID the Google login client is registered for. Nothing secret is in the package:
 * the OAuth client id is public and the private key never leaves the developer's machine. Outside
 * {@link ExtAuthInterceptor} ({@code /ext/**}) on purpose — you download it before logging in.
 */
@RestController
public class ExtensaoDownloadController {

    private static final String PASTA_NO_ZIP = "tma-tme-extensao/";
    private static final Pattern VERSAO = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"");

    private final Path dir;
    private volatile Pacote cache;

    private record Pacote(long modificadoEm, String versao, byte[] zip) {
    }

    public ExtensaoDownloadController(@Value("${app.extensao.dir:/app/extension}") String dir) {
        this.dir = Path.of(dir);
    }

    @GetMapping(value = "/download/tma-tme-extensao.zip", produces = "application/zip")
    public ResponseEntity<byte[]> zip() {
        Pacote p = pacote();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("tma-tme-extensao-" + p.versao() + ".zip").build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .body(p.zip());
    }

    /** The extension's own icon (the Sebratel ball, same as DHO's), for the install page. */
    @GetMapping(value = "/download/icone.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> icone() throws IOException {
        Path icone = dir.resolve("icons/icon128.png");
        if (!Files.isRegularFile(icone)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "max-age=3600").body(Files.readAllBytes(icone));
    }

    @GetMapping(value = "/download", produces = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
    public String pagina() {
        return PAGINA.replace("{{VERSAO}}", pacote().versao());
    }

    /** Zip rebuilt only when a file changes (a redeploy); otherwise served from memory. */
    private Pacote pacote() {
        List<Path> arquivos = listar();
        long modificado = arquivos.stream().mapToLong(this::modificadoEm).max().orElse(0);
        Pacote atual = cache;
        if (atual != null && atual.modificadoEm() == modificado) {
            return atual;
        }
        try {
            String manifest = Files.readString(dir.resolve("manifest.json"), StandardCharsets.UTF_8);
            Matcher m = VERSAO.matcher(manifest);
            String versao = m.find() ? m.group(1) : "0";
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                for (Path f : arquivos) {
                    zip.putNextEntry(new ZipEntry(PASTA_NO_ZIP + dir.relativize(f).toString().replace('\\', '/')));
                    Files.copy(f, zip);
                    zip.closeEntry();
                }
            }
            atual = new Pacote(modificado, versao, bytes.toByteArray());
            cache = atual;
            return atual;
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível empacotar a extensão de " + dir, e);
        }
    }

    private List<Path> listar() {
        if (!Files.isRegularFile(dir.resolve("manifest.json"))) {
            throw new IllegalStateException("Pacote da extensão não encontrado no servidor.");
        }
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private long modificadoEm(Path f) {
        try {
            return Files.getLastModifiedTime(f).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    // Página de instalação — tokens do design system Sebratel (DHO: frontend/src/styles/variables.css).
    private static final String PAGINA = """
            <!DOCTYPE html>
            <html lang="pt-BR">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Instalar TMA/TME · Sebratel</title>
            <link rel="icon" type="image/png" href="download/icone.png">
            <link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=DM+Sans:wght@400;500;600;700&display=swap">
            <style>
              :root { color-scheme: light; --primary:#ffb000; --primary-hover:#e09e00; --contrast:#111111; --bg:#f5f1e8;
                --card:#ffffff; --surface-2:#faf7ef; --text:#2a2a28; --text-primary:#111111; --text-secondary:#5e5b50;
                --border:#e0dacb; --border-subtle:#ede8db; --warning-bg:#fff4d6; --warning-text:#8a5a00;
                --shadow-sm:0 1px 2px rgba(40,33,20,.06),0 1px 3px rgba(40,33,20,.1);
                --focus-ring:0 0 0 3px color-mix(in srgb, var(--primary) 55%, transparent); }
              * { box-sizing:border-box; }
              body { margin:0; background:var(--bg); color:var(--text); font-family:'DM Sans',-apple-system,BlinkMacSystemFont,sans-serif;
                -webkit-font-smoothing:antialiased; }
              .wrap { max-width:640px; margin:32px auto; padding:0 16px; }
              .card { background:var(--card); border:1px solid var(--border); border-radius:12px; overflow:hidden; box-shadow:var(--shadow-sm); }
              .masthead { background:var(--primary); color:var(--contrast); padding:22px 32px; font-size:15px; }
              .masthead { display:flex; align-items:center; gap:10px; }
              .masthead img { width:26px; height:26px; }
              .masthead b { letter-spacing:.01em; } .masthead span { opacity:.72; }
              .content { padding:28px 32px 30px; font-size:14px; line-height:1.55; }
              h1 { font-size:20px; color:var(--text-primary); margin:0 0 8px; }
              .marker { display:flex; align-items:center; gap:10px; font-weight:700; color:var(--text-primary); margin:22px 0 10px; }
              .marker::before { content:""; width:4px; height:14px; border-radius:2px; background:var(--primary); }
              .btn { display:inline-flex; align-items:center; justify-content:center; padding:10px 24px; border-radius:6px;
                background:var(--primary); color:var(--contrast); font-weight:600; font-size:14px; text-decoration:none;
                transition:background-color .2s cubic-bezier(.2,0,0,1), box-shadow .2s; }
              .btn:hover { background:var(--primary-hover); box-shadow:var(--shadow-sm); }
              .btn:focus-visible { box-shadow:var(--focus-ring); outline:none; }
              ol { padding-left:20px; margin:0; } li { margin:0 0 10px; }
              code { background:var(--surface-2); border:1px solid var(--border-subtle); border-radius:4px; padding:1px 6px;
                font-size:13px; color:var(--text-primary); user-select:all; }
              .muted { color:var(--text-secondary); font-size:13px; }
              .notice { background:var(--warning-bg); color:var(--warning-text); border-radius:8px; padding:10px 14px; font-size:13px; margin-top:16px; }
              .footer { padding:16px 32px; background:var(--surface-2); border-top:1px solid var(--border-subtle); color:var(--text-secondary); font-size:12px; }
            </style>
            </head>
            <body>
            <div class="wrap"><div class="card">
              <div class="masthead"><img src="download/icone.png" alt=""><div><b>Sebratel</b><span> &middot; TMA/TME</span></div></div>
              <div class="content">
                <h1>Extensão TMA/TME para o Chrome</h1>
                <p>Mostra o seu TMA/TME <b>do dia</b> (Native e Matrix) num widget flutuante em qualquer página.
                   O login é com a sua conta Google <b>@sebratel.com.br</b> e o seu atendente é reconhecido pelo e-mail.</p>
                <p><a class="btn" href="download/tma-tme-extensao.zip">Baixar extensão (versão {{VERSAO}})</a></p>

                <div class="marker">Como instalar</div>
                <ol>
                  <li>Abra o arquivo baixado e clique em <b>Extrair tudo</b>. Guarde a pasta <code>tma-tme-extensao</code>
                      num lugar fixo (ex.: Documentos) — <b>o Chrome carrega a extensão dessa pasta; não apague</b>.</li>
                  <li>No Chrome, abra <code>chrome://extensions</code> (copie e cole na barra de endereço).</li>
                  <li>Ligue o <b>Modo do desenvolvedor</b>, no canto superior direito.</li>
                  <li>Clique em <b>Carregar sem compactação</b> e selecione a pasta <code>tma-tme-extensao</code>.</li>
                  <li>Fixe o ícone da extensão (quebra-cabeça &rarr; alfinete), clique nele e em <b>Entrar com Google</b>.</li>
                </ol>

                <div class="marker">Como atualizar</div>
                <p>Baixe de novo, substitua o conteúdo da pasta <code>tma-tme-extensao</code> e clique em recarregar (&#8635;)
                   no card da extensão em <code>chrome://extensions</code>. Depois, dê F5 nas abas abertas.</p>

                <div class="notice">Seu nome não apareceu ou está errado? Use <b>Fale com seu administrador</b> no popup da extensão.</div>
                <p class="muted" style="margin-top:16px">O Chrome pode mostrar um aviso sobre extensões no modo desenvolvedor — é esperado
                   para extensões internas instaladas desta forma.</p>
              </div>
              <div class="footer">Extensão interna TMA/TME &middot; Sebratel</div>
            </div></div>
            </body>
            </html>
            """;
}
