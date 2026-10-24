package ru.ddia.instrumentation;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/** Measures synchronous requests handled by controller methods. */
public class RequestTimingInterceptor implements HandlerInterceptor {
    private static final String SAMPLE_ATTRIBUTE =
            RequestTimingInterceptor.class.getName() + ".sample";

    private final MeterRegistry registry;
    private final HttpMetricsProperties properties;

    public RequestTimingInterceptor(MeterRegistry registry, HttpMetricsProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        // Keep the sample on the request: the interceptor is shared across threads.
        request.setAttribute(SAMPLE_ATTRIBUTE, Timer.start(registry));
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception exception) {
        Object attribute = request.getAttribute(SAMPLE_ATTRIBUTE);
        if (!(attribute instanceof Timer.Sample sample)) {
            return;
        }
        request.removeAttribute(SAMPLE_ATTRIBUTE);
        Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (!(route instanceof String pattern)) {
            return;
        }
        int status = response.getStatus();
        // An unhandled exception may reach this point before the container sets HTTP 500.
        if (exception != null && status < 400) {
            status = 500;
        }
        Timer timer = registry.timer(properties.name(),
                "pod", properties.pod(),
                "datacenter", properties.datacenter(),
                "deployment", properties.deployment(),
                "method", request.getMethod(),
                "route", pattern,
                "status", String.valueOf(status));
        sample.stop(timer);
    }
}
