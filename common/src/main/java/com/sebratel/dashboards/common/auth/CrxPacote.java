package com.sebratel.dashboards.common.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A packed Chrome extension (CRX3) with the two facts the update manifest must repeat: its version and
 * its ID. Both are read from the package itself ({@code manifest.json} inside the zip), so the
 * {@code updates.xml} can never disagree with the {@code .crx} it points to. The ID is derived from the
 * manifest's public {@code key} (first 16 bytes of its SHA-256, each nibble mapped to a-p) — the same
 * rule Chrome uses.
 */
public record CrxPacote(String versao, String id, byte[] bytes) {

    private static final byte[] MAGIC = "Cr24".getBytes(StandardCharsets.US_ASCII);
    private static final int VERSAO_CRX = 3;
    private static final int MAX_MANIFEST = 1 << 20;
    private static final Pattern VERSAO_VALIDA = Pattern.compile("\\d+(\\.\\d+){0,3}");
    private static final ObjectMapper JSON = new ObjectMapper();

    public static CrxPacote ler(byte[] crx) {
        try {
            JsonNode manifest = JSON.readTree(manifestDe(crx));
            String versao = manifest.path("version").asText("");
            String chave = manifest.path("key").asText("");
            if (!VERSAO_VALIDA.matcher(versao).matches()) {
                throw new IllegalArgumentException("version inválida no manifest do CRX");
            }
            if (chave.isEmpty()) {
                throw new IllegalArgumentException("o manifest do CRX não tem 'key' (o ID não é determinístico)");
            }
            return new CrxPacote(versao, idDaChave(Base64.getDecoder().decode(chave)), crx);
        } catch (IOException e) {
            throw new IllegalArgumentException("CRX ilegível", e);
        }
    }

    private static byte[] manifestDe(byte[] crx) throws IOException {
        if (crx.length < 12 || !java.util.Arrays.equals(java.util.Arrays.copyOf(crx, 4), MAGIC)) {
            throw new IllegalArgumentException("não é um CRX (magic 'Cr24' ausente)");
        }
        ByteBuffer cabecalho = ByteBuffer.wrap(crx).order(ByteOrder.LITTLE_ENDIAN);
        if (cabecalho.getInt(4) != VERSAO_CRX) {
            throw new IllegalArgumentException("só CRX3 é suportado");
        }
        long inicioZip = 12L + Integer.toUnsignedLong(cabecalho.getInt(8));
        if (inicioZip >= crx.length) {
            throw new IllegalArgumentException("cabeçalho do CRX corrompido");
        }
        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(crx, (int) inicioZip, crx.length - (int) inicioZip))) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                if (e.getName().equals("manifest.json")) {
                    byte[] lido = zip.readNBytes(MAX_MANIFEST + 1);
                    if (lido.length > MAX_MANIFEST) {
                        throw new IllegalArgumentException("manifest.json grande demais");
                    }
                    return lido;
                }
            }
        }
        throw new IllegalArgumentException("manifest.json não encontrado no CRX");
    }

    static String idDaChave(byte[] chavePublicaDer) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(chavePublicaDer);
            StringBuilder id = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                id.append((char) ('a' + ((hash[i] >> 4) & 0xF))).append((char) ('a' + (hash[i] & 0xF)));
            }
            return id.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
