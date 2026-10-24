package ru.ddia.instrumentation;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("metrics")
public record HttpMetricsProperties(
        @DefaultValue("http.requests") @NotBlank String name,
        @DefaultValue("1") @NotBlank String pod,
        @DefaultValue("local") @NotBlank String datacenter,
        @DefaultValue("local") @NotBlank String deployment,
        @DefaultValue({"/actuator", "/actuator/**", "/error"}) List<String> excludedPaths) {
}
