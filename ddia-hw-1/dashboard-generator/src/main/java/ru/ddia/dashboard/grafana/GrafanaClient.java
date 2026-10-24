package ru.ddia.dashboard.grafana;

import com.grafana.foundation.dashboardv2.Dashboard;
import com.grafana.foundation.resource.ManifestBuilder;
import com.grafana.foundation.resource.Resource;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.Map;
import ru.ddia.dashboard.http.HttpApi;

public final class GrafanaClient {
    private static final String DASHBOARDS = "/apis/dashboard.grafana.app/v2/namespaces/default/dashboards";
    private final HttpApi api;

    public GrafanaClient(HttpClient client, URI grafanaUrl, String token) {
        if (token.isBlank()) {
            throw new IllegalArgumentException("Service Account Token file is empty");
        }
        api = new HttpApi(client, grafanaUrl, token);
    }

    public void save(String uid, String folderUid, Dashboard dashboard) throws Exception {
        var existing = api.find(DASHBOARDS + "/" + uid, VersionedDashboard.class);
        var metadata = Resource.named(uid).annotations(Map.of("grafana.app/folder", folderUid));
        if (existing.isPresent()) {
            metadata.resourceVersion(existing.get().metadata().resourceVersion());
        }
        var manifest = new ManifestBuilder()
                .apiVersion("dashboard.grafana.app/v2")
                .kind("Dashboard")
                .metadata(metadata)
                .spec(dashboard)
                .build();
        String method = existing.isPresent() ? "PUT" : "POST";
        String path = existing.isPresent() ? DASHBOARDS + "/" + uid : DASHBOARDS;
        api.send(method, path, manifest.toJSON());
    }

    private record VersionedDashboard(ResourceMetadata metadata) {
    }

    private record ResourceMetadata(String resourceVersion) {
    }
}
