package ru.ddia.dashboard.grafana;

import com.grafana.foundation.dashboardv2.CustomVariableBuilder;
import com.grafana.foundation.dashboardv2.Dashboard;
import com.grafana.foundation.dashboardv2.DashboardBuilder;
import com.grafana.foundation.dashboardv2.Dashboardv2DataQueryKindDatasourceBuilder;
import com.grafana.foundation.dashboardv2.DataQueryKind;
import com.grafana.foundation.dashboardv2.GridBuilder;
import com.grafana.foundation.dashboardv2.GridItemBuilder;
import com.grafana.foundation.dashboardv2.PanelBuilder;
import com.grafana.foundation.dashboardv2.QueryGroupBuilder;
import com.grafana.foundation.dashboardv2.QueryVariableBuilder;
import com.grafana.foundation.dashboardv2.StringOrArrayOfString;
import com.grafana.foundation.dashboardv2.TargetBuilder;
import com.grafana.foundation.dashboardv2.TimeSettingsBuilder;
import com.grafana.foundation.dashboardv2.VariableOption;
import com.grafana.foundation.dashboardv2.VariableRefresh;
import com.grafana.foundation.dashboardv2.VariableSort;
import com.grafana.foundation.prometheus.PrometheusQueryV2Builder;
import com.grafana.foundation.timeseries.TimeseriesVisualizationV2Builder;
import java.util.ArrayList;
import java.util.List;
import ru.ddia.dashboard.cli.CliOptions;
import ru.ddia.dashboard.discovery.Endpoint;
import ru.ddia.dashboard.metrics.HttpMetricQueries;

/** Time-series panels and selectors built with Grafana Foundation SDK. */
public final class DashboardDefinition {
    private DashboardDefinition() {
    }

    public static Dashboard build(List<Endpoint> endpoints, CliOptions options) {
        var queries = new HttpMetricQueries(options.metricName());
        var dashboard = new DashboardBuilder(options.dashboardTitle()).editable(false)
                .description("HTTP latency, request rate, and errors for application endpoints.")
                .tags(List.of("ddia", "http-metrics"))
                .timeSettings(new TimeSettingsBuilder().from("now-15m").to("now").autoRefresh("5s"))
                .queryVariable(variable("deployment", "Deployment", queries.deploymentValues(), "All", options))
                .queryVariable(variable("datacenter", "Datacenter", queries.datacenterValues(), "All", options))
                .queryVariable(variable("pod", "Pod", queries.podValues(), "All", options))
                .customVariable(latencyStatus());
        var grid = new GridBuilder();
        int panelIndex = 0;
        for (Endpoint endpoint : endpoints) {
            String title = endpoint.method() + " " + endpoint.route();
            var latency = new ArrayList<Query>();
            for (double quantile : new double[] {0.5, 0.75, 0.9, 0.95, 0.99}) {
                latency.add(new Query(queries.latency(endpoint, quantile), "p" + Math.round(quantile * 100)));
            }
            addPanel(dashboard, grid, panelIndex++, title + " · Latency", "s", latency, options);
            addPanel(dashboard, grid, panelIndex++, title + " · RPS", "reqps",
                    List.of(new Query(queries.requestsPerSecond(endpoint), "requests/s")), options);
            addPanel(dashboard, grid, panelIndex++, title + " · HTTP errors (%)", "percentunit",
                    List.of(new Query(queries.errorRatio(endpoint), "HTTP {{status}}")), options);
        }
        return dashboard.gridLayout(grid).build();
    }

    private static void addPanel(DashboardBuilder dashboard, GridBuilder grid, int index,
            String title, String unit, List<Query> queries, CliOptions options) {
        var data = new QueryGroupBuilder();
        for (int i = 0; i < queries.size(); i++) {
            Query query = queries.get(i);
            String refId = String.valueOf((char) ('A' + i));
            data.target(new TargetBuilder().refId(refId).query(new PrometheusQueryV2Builder()
                    .datasource(new Dashboardv2DataQueryKindDatasourceBuilder().name(options.datasourceUid()))
                    .expr(query.expression()).legendFormat(query.legend()).refId(refId).range(true).instant(false)));
        }
        String key = "panel-" + (index + 1);
        dashboard.panel(key, new PanelBuilder().id((double) index + 1).title(title).data(data)
                .visualization(new TimeseriesVisualizationV2Builder().unit(unit).min(0.0)));
        grid.item(new GridItemBuilder().name(key).x((index % 3) * 8L).y((index / 3) * 8L).width(8L).height(8L));
    }

    private static QueryVariableBuilder variable(
            String name, String label, String expression, String initialValue, CliOptions options) {
        // Variable queries have their own Prometheus plugin model, unlike time-series targets.
        var query = new DataQueryKind();
        query.kind = "DataQuery";
        query.group = "prometheus";
        query.version = "v0";
        query.datasource = new Dashboardv2DataQueryKindDatasourceBuilder().name(options.datasourceUid()).build();
        query.spec = new PrometheusVariableQuery(expression, "StandardVariableQuery");
        String value = initialValue.equals("All") ? "$__all" : initialValue;
        var current = new VariableOption(true, StringOrArrayOfString.createArrayOfString(List.of(initialValue)),
                StringOrArrayOfString.createArrayOfString(List.of(value)), null);
        return new QueryVariableBuilder(name).label(label).query(() -> query).definition(expression)
                .multi(true).includeAll(true).allValue(".*").current(current)
                .refresh(VariableRefresh.ON_TIME_RANGE_CHANGED).sort(VariableSort.ALPHABETICAL_ASC);
    }

    private static CustomVariableBuilder latencyStatus() {
        var success = new VariableOption(true,
                StringOrArrayOfString.createArrayOfString(List.of("2xx")),
                StringOrArrayOfString.createArrayOfString(List.of("2..")), null);
        return new CustomVariableBuilder("latencyStatus").label("Latency HTTP class")
                .query("2xx : 2.., 3xx : 3.., 4xx : 4.., 5xx : 5..")
                .multi(true).includeAll(true).allValue(".*").current(success)
                .allowCustomValue(false);
    }

    private record Query(String expression, String legend) {
    }

    private record PrometheusVariableQuery(String query, String refId) {
    }
}
