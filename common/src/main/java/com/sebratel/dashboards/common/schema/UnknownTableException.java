package com.sebratel.dashboards.common.schema;

public class UnknownTableException extends RuntimeException {
    public UnknownTableException(String tableName) {
        super("Table not exposed by this service: " + tableName);
    }
}
