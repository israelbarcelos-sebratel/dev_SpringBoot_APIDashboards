package com.sebratel.dashboards.common.auth;

/**
 * HTML e-mail layout in the Sebratel design system — the same tokens, masthead, card and footer as
 * the DHO system's e-mails ({@code dho/backend/.../EmailNotificationService}, tokens from its
 * {@code frontend/src/styles/variables.css}): amber masthead, 600px white card on the corporate
 * beige, DM Sans, inline styles only (e-mail clients strip stylesheets).
 */
final class SebratelEmailTemplate {

    // Tokens replicados do DHO (paleta corporativa GER/Sebratel, tema claro único).
    private static final String COLOR_PRIMARY = "#ffb000"; // âmbar — ação principal
    private static final String COLOR_PRIMARY_CONTRAST = "#111111"; // texto sobre âmbar (AA)
    private static final String COLOR_CARD = "#ffffff";
    private static final String COLOR_TEXT_PRIMARY = "#111111";
    private static final String COLOR_TEXT_SECONDARY = "#5e5b50";
    private static final String COLOR_TEXT_BODY = "#2a2a28";
    private static final String COLOR_BORDER = "#e0dacb";
    private static final String COLOR_BORDER_SUBTLE = "#ede8db";
    private static final String COLOR_BG = "#f5f1e8"; // bege corporativo
    private static final String COLOR_SURFACE_2 = "#faf7ef";
    private static final String COLOR_WARNING_BG = "#fff4d6";
    private static final String COLOR_WARNING_TEXT = "#8a5a00";
    private static final String FONT_FAMILY = "'DM Sans',-apple-system,BlinkMacSystemFont,Arial,sans-serif";

    private static final String SISTEMA = "TMA/TME";

    private SebratelEmailTemplate() {
    }

    /** Masthead "Sebratel · TMA/TME", card with {@code bodyHtml}, automatic-sending footer. */
    static String layout(String bodyHtml) {
        return "<!DOCTYPE html><html lang=\"pt-BR\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"
                + "<link rel=\"stylesheet\" href=\"https://fonts.googleapis.com/css2?family=DM+Sans:wght@400;500;600;700&display=swap\">"
                + "</head>"
                + "<body style=\"margin:0;padding:0;background-color:" + COLOR_BG + ";font-family:" + FONT_FAMILY + ";\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background-color:"
                + COLOR_BG + ";padding:32px 0;\">"
                + "<tr><td align=\"center\">"
                + "<table role=\"presentation\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" style=\"background-color:"
                + COLOR_CARD + ";border-radius:12px;overflow:hidden;border:1px solid " + COLOR_BORDER + ";\">"
                + "<tr><td style=\"background-color:" + COLOR_PRIMARY + ";padding:22px 32px;\">"
                + "<span style=\"font-size:15px;font-weight:700;color:" + COLOR_PRIMARY_CONTRAST
                + ";letter-spacing:0.01em;\">Sebratel</span>"
                + "<span style=\"font-size:15px;color:" + COLOR_PRIMARY_CONTRAST + ";opacity:0.72;\"> &middot; " + SISTEMA + "</span>"
                + "</td></tr>"
                + "<tr><td style=\"padding:28px 32px 30px;color:" + COLOR_TEXT_BODY + ";font-size:14px;line-height:1.5;\">"
                + bodyHtml
                + "</td></tr>"
                + "<tr><td style=\"padding:16px 32px;background-color:" + COLOR_SURFACE_2 + ";border-top:1px solid "
                + COLOR_BORDER_SUBTLE + ";color:" + COLOR_TEXT_SECONDARY + ";font-size:12px;\">"
                + "Enviado automaticamente pela extensão " + SISTEMA + " &middot; Sebratel"
                + "</td></tr>"
                + "</table>"
                + "</td></tr>"
                + "</table>"
                + "</body></html>";
    }

    /** Page title inside the card. */
    static String title(String text) {
        return "<p style=\"font-size:18px;font-weight:700;color:" + COLOR_TEXT_PRIMARY + ";margin:0 0 12px;\">"
                + escape(text) + "</p>";
    }

    static String greeting(String name) {
        return "<p style=\"font-size:14px;color:" + COLOR_TEXT_BODY + ";margin:0 0 10px;\">Olá, <strong style=\"color:"
                + COLOR_TEXT_PRIMARY + ";\">" + escape(name) + "</strong>,</p>";
    }

    /** Paragraph; the argument is trusted HTML (escape user values before passing them in). */
    static String lead(String html) {
        return "<p style=\"font-size:14px;color:" + COLOR_TEXT_BODY + ";line-height:1.5;margin:0 0 12px;\">" + html + "</p>";
    }

    /** Section marker: 4px amber accent + title. */
    static String sectionMarker(String text) {
        return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin-top:18px;\"><tr>"
                + "<td width=\"4\" height=\"14\" style=\"background-color:" + COLOR_PRIMARY + ";border-radius:2px;\"></td>"
                + "<td style=\"padding-left:10px;\"><span style=\"font-size:14px;font-weight:700;color:" + COLOR_TEXT_PRIMARY
                + ";\">" + escape(text) + "</span></td></tr></table>";
    }

    static String divider() {
        return "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\">"
                + "<tr><td style=\"padding:16px 0 0;\"><div style=\"border-top:1px solid " + COLOR_BORDER_SUBTLE
                + ";\"></div></td></tr></table>";
    }

    static String fieldRow(String label, String value) {
        return "<tr>"
                + "<td width=\"140\" valign=\"top\" style=\"padding:6px 0;color:" + COLOR_TEXT_SECONDARY + ";font-size:13.5px;\">"
                + escape(label) + "</td>"
                + "<td valign=\"top\" style=\"padding:6px 0;color:" + COLOR_TEXT_PRIMARY + ";font-weight:700;font-size:13.5px;\">"
                + escape(value) + "</td>"
                + "</tr>";
    }

    static String fieldTable(String... rows) {
        return "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin-top:10px;\">"
                + String.join("", rows) + "</table>";
    }

    /** User-written text (escaped), keeping its line breaks, in a subtle quote box. */
    static String quote(String plainText) {
        return "<div style=\"margin-top:10px;padding:12px 14px;background-color:" + COLOR_SURFACE_2 + ";border:1px solid "
                + COLOR_BORDER_SUBTLE + ";border-radius:8px;font-size:14px;color:" + COLOR_TEXT_BODY + ";line-height:1.5;\">"
                + escape(plainText).replace("\n", "<br/>") + "</div>";
    }

    /** Amber-tinted notice (e.g. "no binding found"). */
    static String notice(String html) {
        return "<div style=\"margin-top:14px;padding:10px 14px;background-color:" + COLOR_WARNING_BG
                + ";border-radius:8px;color:" + COLOR_WARNING_TEXT + ";font-size:13px;\">" + html + "</div>";
    }

    static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
