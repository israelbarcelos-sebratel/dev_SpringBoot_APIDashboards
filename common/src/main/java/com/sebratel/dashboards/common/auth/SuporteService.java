package com.sebratel.dashboards.common.auth;

import com.sebratel.dashboards.common.auth.UsuarioRepository.Usuario;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.sebratel.dashboards.common.auth.SebratelEmailTemplate.*;

/**
 * "Fale com seu administrador" of the Chrome extension: e-mails the development team
 * ({@code app.suporte.email}) on the user's behalf, with Reply-To set to the user so the team can
 * answer directly. Same approach as the DHO system (Spring Mail over SMTP, HTML body built in code),
 * sent from the authenticated account ({@code spring.mail.username}) — Gmail would rewrite any other
 * From.
 *
 * <p>Layout: {@link SebratelEmailTemplate} (the Sebratel design system, same as DHO's e-mails).
 *
 * <p>{@code app.suporte.teste-para} (MAIL_TEST_TO), when set, sends a test e-mail plus an example
 * support request at startup, to validate a deploy's SMTP settings; leave it empty otherwise.
 */
@Service
public class SuporteService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SuporteService.class);
    private static final Duration INTERVALO_MINIMO = Duration.ofMinutes(2);
    private static final int MAX_MENSAGEM = 2000;

    private final ObjectProvider<JavaMailSender> mailSender;
    private final String remetente;
    private final String destino;
    private final String testePara;
    private final Map<String, Instant> ultimoEnvio = new ConcurrentHashMap<>();

    public SuporteService(ObjectProvider<JavaMailSender> mailSender,
                          @Value("${spring.mail.username:}") String remetente,
                          @Value("${app.suporte.email:desenvolvimento@sebratel.com.br}") String destino,
                          @Value("${app.suporte.teste-para:}") String testePara) {
        this.mailSender = mailSender;
        this.remetente = remetente;
        this.destino = destino;
        this.testePara = testePara;
    }

    /** Sends the user's request to the development team. At most one every 2 minutes per user. */
    public void pedirAjuste(Usuario u, String mensagem) {
        Instant agora = Instant.now();
        Instant anterior = ultimoEnvio.get(u.email());
        if (anterior != null && anterior.plus(INTERVALO_MINIMO).isAfter(agora)) {
            throw new AuthException(429, "Seu pedido já foi enviado. Aguarde alguns minutos para enviar outro.");
        }
        String texto = mensagem == null ? "" : mensagem.strip();
        if (texto.length() > MAX_MENSAGEM) {
            texto = texto.substring(0, MAX_MENSAGEM);
        }
        enviar(destino, u.email(), "[TMA/TME] Ajuste de usuário — " + u.email(), corpoPedido(u, texto));
        ultimoEnvio.put(u.email(), agora);
    }

    private static String corpoPedido(Usuario u, String texto) {
        boolean semVinculo = u.nomes().isEmpty();
        String vinculo = semVinculo
                ? "Nenhum (e-mail não encontrado na Matrix)"
                : String.join(", ", u.nomes()) + (UsuarioRepository.VINCULO_MANUAL.equals(u.vinculo())
                        ? " (definido por administrador)" : " (automático, pela Matrix)");

        return title("Pedido de ajuste de usuário")
                + lead("<strong>" + escape(u.email()) + "</strong> pediu ajuda pelo botão "
                        + "<em>Fale com seu administrador</em> da extensão TMA/TME.")
                + sectionMarker("Usuário")
                + fieldTable(
                        fieldRow("E-mail", u.email()),
                        fieldRow("Vínculo atual", vinculo),
                        fieldRow("Papel", UsuarioRepository.ADMIN.equals(u.role()) ? "Administrador" : "Usuário comum"))
                + sectionMarker("Mensagem")
                + quote(texto.isEmpty() ? "(sem mensagem)" : texto)
                + (semVinculo ? notice("Sem vínculo, o usuário não vê nenhum dado no widget até ser vinculado.") : "")
                + divider()
                + lead("<span style=\"font-size:13px;\">Responda este e-mail para falar direto com o usuário. "
                        + "Para vincular manualmente: popup da extensão &rarr; <strong>Gerenciar usuários</strong>.</span>");
    }

    @Override
    public void run(ApplicationArguments args) {
        if (testePara.isBlank()) {
            return;
        }
        try {
            enviar(testePara, null, "[TMA/TME] Teste de envio de e-mail",
                    title("Teste de envio de e-mail")
                            + lead("Este é um teste do envio de e-mails das APIs de dashboards, usado pelo botão "
                                    + "<em>Fale com seu administrador</em> da extensão TMA/TME.")
                            + sectionMarker("Configuração")
                            + fieldTable(
                                    fieldRow("Remetente", remetente),
                                    fieldRow("Pedidos vão para", destino))
                            + divider()
                            + lead("<span style=\"font-size:13px;\">Se você recebeu esta mensagem, o SMTP está "
                                    + "configurado corretamente.</span>"));
            // Exemplo de como chega um pedido real (sem vínculo), para revisar o layout.
            Usuario exemplo = new Usuario(testePara, null, List.of(), UsuarioRepository.USER, null, null);
            enviar(testePara, testePara, "[TMA/TME] Exemplo — Ajuste de usuário — " + testePara,
                    corpoPedido(exemplo, "Meu nome não aparece no widget.\nPodem vincular meu e-mail ao meu usuário da Matrix?"));
            log.info("E-mails de teste enviados para {}.", testePara);
        } catch (AuthException e) {
            log.warn("Falha no e-mail de teste para {}: {}", testePara, e.getMessage());
        }
    }

    private void enviar(String para, String responderPara, String assunto, String htmlCorpo) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null || remetente.isBlank()) {
            throw new AuthException(503, "Envio de e-mail não configurado no servidor.");
        }
        try {
            MimeMessage msg = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, false, "UTF-8");
            helper.setFrom(remetente, "TMA/TME Sebratel");
            helper.setTo(para);
            if (responderPara != null) {
                helper.setReplyTo(responderPara);
            }
            helper.setSubject(assunto);
            helper.setText(layout(htmlCorpo), true);
            sender.send(msg);
        } catch (Exception e) {
            log.error("Falha ao enviar e-mail para {}: {}", para, e.getMessage());
            throw new AuthException(502, "Não foi possível enviar o e-mail agora. Tente novamente mais tarde.");
        }
    }
}
