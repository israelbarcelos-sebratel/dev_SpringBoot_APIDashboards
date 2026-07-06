package com.sebratel.dashboards.nativeapi;

import com.sebratel.dashboards.common.config.TableGroupProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConfigurationProperties(prefix = "app.group")
public class NativeGroupProperties implements TableGroupProperties {

    private String name;
    private List<String> tables;

    public void setName(String name) {
        this.name = name;
    }

    public void setTables(List<String> tables) {
        this.tables = tables;
    }

    @Override
    public String groupName() {
        return name;
    }

    @Override
    public List<String> tables() {
        return tables;
    }
}
