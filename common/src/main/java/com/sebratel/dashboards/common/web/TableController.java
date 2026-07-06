package com.sebratel.dashboards.common.web;

import com.sebratel.dashboards.common.dto.CorrelationResponse;
import com.sebratel.dashboards.common.dto.RowsPage;
import com.sebratel.dashboards.common.dto.StatsResponse;
import com.sebratel.dashboards.common.dto.TimeSeriesResponse;
import com.sebratel.dashboards.common.schema.TableSchema;
import com.sebratel.dashboards.common.stats.Insight;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class TableController {

    private final TableDataService tableDataService;

    public TableController(TableDataService tableDataService) {
        this.tableDataService = tableDataService;
    }

    @GetMapping("/api/tables")
    public List<String> listTables() {
        return tableDataService.listTables();
    }

    @GetMapping("/api/tables/{table}/schema")
    public TableSchema schema(@PathVariable String table) {
        return tableDataService.getSchema(table);
    }

    @GetMapping("/api/tables/{table}/rows")
    public RowsPage rows(@PathVariable String table,
                          @RequestParam(defaultValue = "0") int page,
                          @RequestParam(defaultValue = "25") int size,
                          @RequestParam(required = false) String sort,
                          @RequestParam(defaultValue = "desc") String dir,
                          @RequestParam Map<String, String> allParams) {
        return tableDataService.getRows(table, page, size, sort, dir, filtersFrom(allParams));
    }

    @GetMapping("/api/tables/{table}/stats")
    public StatsResponse stats(@PathVariable String table, @RequestParam Map<String, String> allParams) {
        return tableDataService.getStats(table, filtersFrom(allParams));
    }

    @GetMapping("/api/tables/{table}/timeseries")
    public TimeSeriesResponse timeSeries(@PathVariable String table,
                                          @RequestParam(required = false) String column,
                                          @RequestParam(defaultValue = "month") String granularity,
                                          @RequestParam Map<String, String> allParams) {
        return tableDataService.getTimeSeries(table, column, granularity, filtersFrom(allParams));
    }

    @GetMapping("/api/tables/{table}/correlations")
    public CorrelationResponse correlations(@PathVariable String table, @RequestParam Map<String, String> allParams) {
        return tableDataService.getCorrelations(table, filtersFrom(allParams));
    }

    @GetMapping("/api/tables/{table}/insights")
    public List<Insight> insights(@PathVariable String table, @RequestParam Map<String, String> allParams) {
        return tableDataService.getInsights(table, filtersFrom(allParams));
    }

    /** Strip pagination/sort/series knobs so only real column filters reach the query layer. */
    private static Map<String, String> filtersFrom(Map<String, String> allParams) {
        Map<String, String> filters = new java.util.HashMap<>(allParams);
        filters.remove("page");
        filters.remove("size");
        filters.remove("sort");
        filters.remove("dir");
        filters.remove("column");
        filters.remove("granularity");
        return filters;
    }
}
