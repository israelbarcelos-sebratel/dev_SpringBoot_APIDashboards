package com.sebratel.dashboards.common.cache;

import com.sebratel.dashboards.common.config.DataSourcesConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stable pseudonyms for customer names, so the extension's per-call table never shows who the
 * customer is: the API only ever sends the alias ("CLI-7KQ2MX"), and the alias -> name relation lives
 * only in the app database ({@code cliente_alias}, see {@code app-schema.sql}).
 *
 * <p>The key is the SHA-256 of the normalized name (trimmed, collapsed spaces, upper case), so the
 * same customer gets the same alias every day and in both systems — matrix-api and native-api share
 * the table. Aliases are random, not sequential, so they say nothing about the customer. Placeholder
 * names ("Não informado") aren't customers and get no alias.
 */
@Component
public class ClienteAlias {

    private static final String PREFIXO = "CLI-";
    // Sem 0/O/1/I/L: o alias é lido e ditado por gente.
    private static final char[] ALFABETO = "23456789ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray();
    private static final int TAMANHO = 6;
    private static final Set<String> SEM_CLIENTE = Set.of("", "NÃO INFORMADO", "NAO INFORMADO", "NULL", "-");

    private final JdbcTemplate db;
    private final SecureRandom random = new SecureRandom();
    /** nome_hash -> alias; aliases never change, so this never goes stale. */
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public ClienteAlias(@Qualifier(DataSourcesConfig.APP) JdbcTemplate db) {
        this.db = db;
    }

    /** Alias of each name (absent for placeholders), creating the missing ones. */
    public Map<String, String> aliases(Collection<String> nomes) {
        Map<String, String> hashPorNome = new LinkedHashMap<>();
        Map<String, String> normalizadoPorHash = new LinkedHashMap<>();
        for (String nome : nomes) {
            String n = normalizar(nome);
            if (n != null) {
                String h = sha256(n);
                hashPorNome.put(nome, h);
                normalizadoPorHash.put(h, n);
            }
        }
        List<String> faltando = normalizadoPorHash.keySet().stream().filter(h -> !cache.containsKey(h)).toList();
        if (!faltando.isEmpty()) {
            carregar(faltando);
            faltando.stream().filter(h -> !cache.containsKey(h)).forEach(h -> criar(h, normalizadoPorHash.get(h)));
        }
        Map<String, String> resultado = new LinkedHashMap<>();
        hashPorNome.forEach((nome, h) -> resultado.put(nome, cache.get(h)));
        return resultado;
    }

    private void carregar(List<String> hashes) {
        for (int i = 0; i < hashes.size(); i += 500) {
            List<String> lote = hashes.subList(i, Math.min(hashes.size(), i + 500));
            db.query("SELECT nome_hash, alias FROM cliente_alias WHERE nome_hash IN ("
                            + String.join(",", Collections.nCopies(lote.size(), "?")) + ")",
                    rs -> {
                        cache.put(rs.getString("nome_hash"), rs.getString("alias"));
                    }, lote.toArray());
        }
    }

    /**
     * INSERT IGNORE + re-read: if the other API (or a concurrent request) created this customer's
     * alias first, we keep theirs; if our random alias collided with another customer's, try again.
     */
    private void criar(String hash, String nome) {
        for (int tentativa = 0; tentativa < 5 && !cache.containsKey(hash); tentativa++) {
            db.update("INSERT IGNORE INTO cliente_alias (nome_hash, alias, nome, criado_em) VALUES (?, ?, ?, NOW())",
                    hash, novoAlias(), nome);
            carregar(List.of(hash));
        }
        if (!cache.containsKey(hash)) {
            throw new IllegalStateException("Não foi possível gerar alias de cliente.");
        }
    }

    private String novoAlias() {
        StringBuilder sb = new StringBuilder(PREFIXO);
        for (int i = 0; i < TAMANHO; i++) {
            sb.append(ALFABETO[random.nextInt(ALFABETO.length)]);
        }
        return sb.toString();
    }

    private static String normalizar(String nome) {
        if (nome == null) {
            return null;
        }
        String n = nome.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        return SEM_CLIENTE.contains(n) ? null : n;
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
