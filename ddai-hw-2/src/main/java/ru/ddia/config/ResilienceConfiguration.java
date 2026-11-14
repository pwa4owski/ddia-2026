package ru.ddia.config;

import ru.ddia.budget.Ticker;
import ru.ddia.multi.RoundRobinClient;
import ru.ddia.multi.HedgingClient;
import ru.ddia.multi.CircuitBreakingClient;
import ru.ddia.multi.CircuitSettings;
import java.time.Duration;
import ru.ddia.strategy.DefaultClient;
import ru.ddia.strategy.RetryClient;
import ru.ddia.strategy.BackoffClient;
import ru.ddia.internal.Sleeper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ResilienceConfiguration {
    @Bean
    Ticker ticker() {
        return Ticker.systemTicker();
    }

    @Bean
    DefaultClient defaultClient(Ticker ticker) {
        return new DefaultClient(ticker);
    }

    @Bean
    RetryClient retryClient(Ticker ticker) {
        return new RetryClient(ticker);
    }

    @Bean
    BackoffClient backoffClient(Ticker ticker) {
        return new BackoffClient(ticker, Sleeper.systemSleeper());
    }

    @Bean
    RoundRobinClient roundRobinClient(Ticker ticker) {
        return new RoundRobinClient(ticker, Sleeper.systemSleeper());
    }

    @Bean
    HedgingClient hedgingClient(Ticker ticker) {
        return new HedgingClient(ticker);
    }

    @Bean
    CircuitBreakingClient circuitBreakingClient(Ticker ticker) {
        return new CircuitBreakingClient(ticker, Sleeper.systemSleeper(),
                new CircuitSettings(10, 0.5, Duration.ofSeconds(5)));
    }
}
