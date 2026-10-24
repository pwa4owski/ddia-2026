package ru.ddia.dashboard;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.ParameterException;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.time.Duration;

import ru.ddia.dashboard.cli.CliOptions;
import ru.ddia.dashboard.discovery.ApplicationClient;
import ru.ddia.dashboard.grafana.DashboardDefinition;
import ru.ddia.dashboard.grafana.GrafanaClient;

/**
 * Discovers application endpoints and publishes their metrics dashboard.
 */
public class DashboardGenerator {
    public static void main(String[] args) {
        System.exit(execute(args));
    }

    private static int execute(String[] args) {
        var options = new CliOptions();
        var commander = JCommander.newBuilder().programName("dashboard-generator").addObject(options).build();
        try {
            commander.parse(args);
        } catch (ParameterException exception) {
            System.err.println(exception.getMessage());
            commander.usage();
            return 2;
        }
        if (options.help()) {
            commander.usage();
            return 0;
        }
        try {
            return run(options);
        } catch (Exception exception) {
            // Never print the token, Authorization header, or API response bodies.
            System.err.println("Dashboard CLI failed: " + exception.getMessage());
            return 1;
        }
    }

    private static int run(CliOptions options) throws Exception {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var endpoints = new ApplicationClient(client, options.appUrl()).endpoints();
        String token = Files.readString(options.tokenFile()).strip();
        var grafana = new GrafanaClient(client, options.grafanaUrl(), token);
        var dashboard = DashboardDefinition.build(endpoints, options);
        grafana.save(options.dashboardUid(), options.folderUid(), dashboard);
        System.out.println("Dashboard ready: /d/" + options.dashboardUid()
                + " (" + endpoints.size() + " endpoints, " + endpoints.size() * 3 + " panels)");
        endpoints.forEach(endpoint -> System.out.println("  " + endpoint.method() + " " + endpoint.route()));
        return 0;
    }

}
