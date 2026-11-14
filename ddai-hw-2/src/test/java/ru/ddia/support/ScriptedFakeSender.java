package ru.ddia.support;

import ru.ddia.api.Request;
import ru.ddia.api.SubrequestCall;
import ru.ddia.api.SubrequestResult;
import ru.ddia.api.SubrequestSender;
import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Scripts are constructor data; the production send contract receives only a request. */
public final class ScriptedFakeSender implements SubrequestSender, AutoCloseable {
    public sealed interface Step {
        record Reply(SubrequestResult result, Duration delay) implements Step {
            public Reply(SubrequestResult result) {
                this(result, Duration.ZERO);
            }
        }

        record Hang() implements Step {
        }

        record Controlled(CompletableFuture<SubrequestResult> source) implements Step {
        }
    }

    public static final class Invocation implements SubrequestCall {
        private final String endpointId;
        private final Request request;
        private final long startedAt = System.nanoTime();
        private final CompletableFuture<SubrequestResult> result = new CompletableFuture<>();
        private OptionalLong completedAt = OptionalLong.empty();
        private OptionalLong cancelledAt = OptionalLong.empty();
        private ScheduledFuture<?> scheduled;

        private Invocation(String endpointId, Request request) {
            this.endpointId = endpointId;
            this.request = request;
        }

        public String endpointId() {
            return endpointId;
        }

        public Request request() {
            return request;
        }

        public long startedAt() {
            return startedAt;
        }

        public synchronized OptionalLong completedAt() {
            return completedAt;
        }

        public synchronized OptionalLong cancelledAt() {
            return cancelledAt;
        }

        private synchronized void complete(SubrequestResult value, Throwable failure) {
            if (result.isDone()) {
                return;
            }
            completedAt = OptionalLong.of(System.nanoTime());
            if (failure == null) {
                result.complete(value);
            } else {
                result.completeExceptionally(failure);
            }
        }

        @Override
        public CompletionStage<SubrequestResult> completion() {
            return result;
        }

        @Override
        public synchronized void cancel() {
            if (cancelledAt.isPresent()) {
                return;
            }
            cancelledAt = OptionalLong.of(System.nanoTime());
            if (scheduled != null) {
                scheduled.cancel(false);
            }
            result.cancel(false);
        }
    }

    private final String endpointId;
    private final List<Step> script;
    private final List<Invocation> calls = new CopyOnWriteArrayList<>();
    private final List<Invocation> journal;
    private final CountDownLatch firstSend = new CountDownLatch(1);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("fake-sender-", 0).factory());

    public ScriptedFakeSender(String endpointId, Step... script) {
        this(endpointId, List.of(script), new CopyOnWriteArrayList<>());
    }

    public ScriptedFakeSender(String endpointId, List<Step> script, List<Invocation> journal) {
        this.endpointId = endpointId;
        this.script = List.copyOf(script);
        this.journal = journal;
    }

    @Override
    public synchronized SubrequestCall send(Request request) {
        int index = calls.size();
        if (index >= script.size()) {
            throw new AssertionError("Unexpected send to " + endpointId + ": " + (index + 1));
        }
        var call = new Invocation(endpointId, request);
        calls.add(call);
        journal.add(call);
        switch (script.get(index)) {
            case Step.Reply reply -> {
                if (reply.delay().isZero()) {
                    call.complete(reply.result(), null);
                } else {
                    call.scheduled = scheduler.schedule(() -> call.complete(reply.result(), null),
                            reply.delay().toNanos(), TimeUnit.NANOSECONDS);
                }
            }
            case Step.Controlled controlled -> controlled.source().whenComplete(call::complete);
            case Step.Hang ignored -> {
                // No worker is blocked: the future remains pending until cancellation.
            }
        }
        firstSend.countDown();
        return call;
    }

    public List<Invocation> calls() {
        return List.copyOf(calls);
    }

    public boolean awaitFirstSend(Duration timeout) throws InterruptedException {
        return firstSend.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    @Override
    public void close() throws InterruptedException {
        for (Invocation call : calls) {
            if (!call.result.isDone()) {
                call.cancel();
            }
        }
        scheduler.shutdownNow();
        if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
            throw new AssertionError("Fake sender scheduler did not terminate");
        }
    }
}
