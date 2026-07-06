package com.sebratel.dashboards.common.config;

import java.util.List;

/**
 * Implemented once per app (matrix-api / native-api) to declare which tables that
 * service is allowed to expose. TableDataService only ever touches tables from this list.
 */
public interface TableGroupProperties {
    String groupName();

    List<String> tables();
}
