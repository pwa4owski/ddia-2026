package ru.ddia.dashboard.metrics;

import java.util.ArrayList;
import java.util.List;
import ru.ddia.dashboard.discovery.Endpoint;

/** PromQL for the HTTP timer and dashboard selectors. */
public final class HttpMetricQueries {
    private final String metricName;

    public HttpMetricQueries(String metricName) {
        this.metricName = metricName;
    }

    public String latency(Endpoint endpoint, double quantile) {
        // Class values are regexes (2.., 4..), so keep them intact when combining selections.
        String buckets = endpointLabels(endpoint)
                .regex("status", "${latencyStatus:pipe}")
                .select(metricName + "_bucket");
        String bucketRates = rate(buckets);
        // Merge the selected instances before calculating their shared percentile.
        return "histogram_quantile(%s, sum by (le) (%s))".formatted(quantile, bucketRates);
    }

    public String requestsPerSecond(Endpoint endpoint) {
        String requests = endpointLabels(endpoint).select(metricName + "_count");
        return "sum(%s)".formatted(rate(requests));
    }

    public String errorRatio(Endpoint endpoint) {
        String errors = endpointLabels(endpoint)
                .regex("status", "[45]..")
                .select(metricName + "_count");
        String requests = endpointLabels(endpoint).select(metricName + "_count");
        return "sum by (status) (%s) / scalar(sum(%s))".formatted(rate(errors), rate(requests));
    }

    public String deploymentValues() {
        return labelValues("deployment", new Labels());
    }

    public String datacenterValues() {
        return labelValues("datacenter", new Labels().dashboardVariable("deployment"));
    }

    public String podValues() {
        return labelValues("pod", new Labels()
                .dashboardVariable("deployment")
                .dashboardVariable("datacenter"));
    }

    private Labels endpointLabels(Endpoint endpoint) {
        return new Labels()
                .equal("method", endpoint.method())
                .equal("route", endpoint.route())
                .dashboardVariable("deployment")
                .dashboardVariable("datacenter")
                .dashboardVariable("pod");
    }

    private String labelValues(String label, Labels labels) {
        return "label_values(%s, %s)".formatted(labels.select(metricName + "_count"), label);
    }

    private static String rate(String series) {
        return "rate(%s[$__rate_interval])".formatted(series);
    }

    private static final class Labels {
        private final List<String> matchers = new ArrayList<>();

        Labels equal(String name, String value) {
            return add(name, "=", value);
        }

        Labels regex(String name, String value) {
            return add(name, "=~", value);
        }

        Labels dashboardVariable(String name) {
            return regex(name, "${" + name + ":regex}");
        }

        String select(String metric) {
            return matchers.isEmpty() ? metric : "%s{%s}".formatted(metric, String.join(",", matchers));
        }

        private Labels add(String name, String operator, String value) {
            matchers.add("%s%s\"%s\"".formatted(name, operator, escape(value)));
            return this;
        }

        // Route templates are literal PromQL label values, not query fragments.
        private static String escape(String value) {
            return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        }
    }
}
