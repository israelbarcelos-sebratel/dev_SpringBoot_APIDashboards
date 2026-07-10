package com.sebratel.dashboards.common.semantic;

/** Raised when a semantic route names a domain this service does not expose — surfaces as a 404. */
public class UnknownDomainException extends RuntimeException {
    public UnknownDomainException(String domain) {
        super("Domínio não disponível neste serviço: " + domain);
    }
}
