package ru.ddia.instrumentation;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({DispatcherServlet.class, MeterRegistry.class})
@ConditionalOnProperty(prefix = "metrics", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(HttpMetricsProperties.class)
public class HttpMetricsAutoConfiguration {
    @Bean
    WebMvcConfigurer httpMetricsWebMvcConfigurer(MeterRegistry registry, HttpMetricsProperties properties) {
        var interceptor = new RequestTimingInterceptor(registry, properties);
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry interceptors) {
                interceptors.addInterceptor(interceptor).excludePathPatterns(properties.excludedPaths());
            }
        };
    }
}
