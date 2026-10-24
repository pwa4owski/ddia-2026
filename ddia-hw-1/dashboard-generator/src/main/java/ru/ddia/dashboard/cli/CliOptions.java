package ru.ddia.dashboard.cli;

import com.beust.jcommander.IStringConverter;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

public final class CliOptions {
    @Parameter(names = "--app-url",
            converter = HttpUrlConverter.class, description = "Application base URL")
    private URI appUrl = URI.create("http://localhost:8080");

    @Parameter(names = "--grafana-url",
            converter = HttpUrlConverter.class, description = "Grafana base URL")
    private URI grafanaUrl = URI.create("http://localhost:3000");

    @Parameter(names = "--token-file", required = true,
            description = "File containing a Grafana Service Account Token")
    private Path tokenFile;

    @Parameter(names = "--datasource-uid",
            description = "Existing Grafana data source UID")
    private String datasourceUid = "victoriametrics";

    @Parameter(names = "--folder-uid", description = "Grafana folder prepared for generated dashboards")
    private String folderUid = "ddia-hw-1";

    @Parameter(names = "--dashboard-title",
            description = "Dashboard title")
    private String dashboardTitle = "Service metrics";

    @Parameter(names = "--metric-name", description = "Prometheus histogram name without _bucket/_count suffix")
    private String metricName = "bookings_request_seconds";

    @Parameter(names = {"-h", "--help"}, help = true, description = "Show usage")
    private boolean help;

    public boolean help() {
        return help;
    }

    public URI appUrl() {
        return appUrl;
    }

    public URI grafanaUrl() {
        return grafanaUrl;
    }

    public Path tokenFile() {
        return tokenFile;
    }

    public String datasourceUid() {
        return datasourceUid;
    }

    public String dashboardTitle() {
        return dashboardTitle;
    }

    public String folderUid() {
        return folderUid;
    }

    public String dashboardUid() {
        String identity = appUrl.normalize().toString().replaceAll("/+$", "") + "|" + datasourceUid;
        return "service-" + UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8))
                .toString().replace("-", "");
    }

    public String metricName() {
        if (!metricName.matches("[a-zA-Z_:][a-zA-Z0-9_:]*")) {
            throw new ParameterException("Invalid Prometheus metric name");
        }
        return metricName;
    }

    public static final class HttpUrlConverter implements IStringConverter<URI> {
        @Override
        public URI convert(String value) {
            URI uri = URI.create(value);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null) {
                throw new ParameterException("Expected an HTTP(S) base URL without credentials or query");
            }
            return uri;
        }
    }
}
