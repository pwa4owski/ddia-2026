package ru.ddia;

import ru.ddia.api.OperationResult;
import ru.ddia.api.Endpoint;
import ru.ddia.api.Endpoints;
import ru.ddia.api.Request;
import ru.ddia.api.SubrequestResult;
import ru.ddia.budget.LatencyBudget;
import ru.ddia.budget.RetryBudget;
import ru.ddia.multi.RoundRobinClient;
import ru.ddia.multi.RoundRobinSettings;
import ru.ddia.multi.HedgingClient;
import ru.ddia.multi.HedgingSettings;
import ru.ddia.multi.CircuitBreakingClient;
import ru.ddia.strategy.RetryClient;
import ru.ddia.strategy.RetrySettings;
import ru.ddia.strategy.BackoffClient;
import ru.ddia.strategy.BackoffSettings;
import ru.ddia.strategy.ExponentialBackoff;
import ru.ddia.strategy.DefaultClient;
import ru.ddia.strategy.DefaultSettings;
import ru.ddia.support.ScriptedFakeSender;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResilienceApplicationTest {
    @Test
    @Timeout(30)
    void bootContextProvidesWorkingClientWithoutExternalInfrastructure() throws InterruptedException {
        var application = new SpringApplication(ResilienceApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        try (var context = application.run("--spring.main.banner-mode=off");
             var sender = new ScriptedFakeSender("A",
                     new ScriptedFakeSender.Step.Reply(new SubrequestResult.Success(200, "spring")))) {
            var client = context.getBean(DefaultClient.class);
            var settings = new DefaultSettings(new LatencyBudget(Duration.ofSeconds(3)));

            assertEquals(new OperationResult.Success(200, "spring", 1),
                    client.execute(new Request("payload"), settings, sender));
            assertEquals(1, sender.calls().size());
            try (var retrySender = new ScriptedFakeSender("A",
                    new ScriptedFakeSender.Step.Reply(new SubrequestResult.ServerError(503)),
                    new ScriptedFakeSender.Step.Reply(new SubrequestResult.Success(200, "retry")));
                 var backoffSender = new ScriptedFakeSender("B",
                         new ScriptedFakeSender.Step.Reply(new SubrequestResult.ServerError(503)),
                         new ScriptedFakeSender.Step.Reply(new SubrequestResult.Success(200, "backoff")))) {
                var budget = new RetryBudget(2, 2, 2, 2);
                assertEquals(new OperationResult.Success(200, "retry", 2),
                        context.getBean(RetryClient.class).execute(new Request("payload"),
                                new RetrySettings(budget, settings.latencyBudget()), retrySender));
                assertEquals(new OperationResult.Success(200, "backoff", 2),
                        context.getBean(BackoffClient.class).execute(new Request("payload"),
                                new BackoffSettings(budget, settings.latencyBudget(),
                                        new ExponentialBackoff(Duration.ofMillis(1), 2, Duration.ofMillis(2))),
                                backoffSender));
                assertEquals(2, retrySender.calls().size());
                assertEquals(2, backoffSender.calls().size());
            }
            try (var a = new ScriptedFakeSender("A",
                    new ScriptedFakeSender.Step.Reply(new SubrequestResult.ServerError(503)));
                 var b = new ScriptedFakeSender("B",
                         new ScriptedFakeSender.Step.Reply(new SubrequestResult.Success(200, "round-robin")))) {
                assertEquals(new OperationResult.Success(200, "round-robin", 2),
                        context.getBean(RoundRobinClient.class).execute(new Request("payload"),
                                new RoundRobinSettings(new RetryBudget(2, 2, 2, 2), settings.latencyBudget()),
                                new Endpoints(List.of(new Endpoint("A", a), new Endpoint("B", b)))));
                assertEquals(1, a.calls().size());
                assertEquals(1, b.calls().size());
            }
            try (var hedge = new ScriptedFakeSender("hedge",
                    new ScriptedFakeSender.Step.Reply(new SubrequestResult.Success(200, "hedged")));
                 var circuit = new ScriptedFakeSender("circuit",
                         new ScriptedFakeSender.Step.Reply(new SubrequestResult.Success(200, "protected")))) {
                assertEquals(new OperationResult.Success(200, "hedged", 1),
                        context.getBean(HedgingClient.class).execute(new Request("payload"),
                                new HedgingSettings(Duration.ofSeconds(3), Duration.ofMillis(10)),
                                new Endpoints(List.of(new Endpoint("hedge", hedge)))));
                assertEquals(new OperationResult.Success(200, "protected", 1),
                        context.getBean(CircuitBreakingClient.class).execute(new Request("payload"),
                                new RoundRobinSettings(new RetryBudget(2, 2, 2, 2), settings.latencyBudget()),
                                new Endpoints(List.of(new Endpoint("circuit", circuit)))));
            }
        }
    }
}
