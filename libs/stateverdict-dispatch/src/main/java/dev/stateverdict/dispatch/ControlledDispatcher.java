package dev.stateverdict.dispatch;

import dev.stateverdict.dispatch.JournalEntry.Action;
import dev.stateverdict.dispatch.JournalEntry.Outcome;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Test-mode dispatcher that applies scheduled {@link Intervention}s and records every action in a
 * per-run journal.
 *
 * <h2>Semantics</h2>
 * <ul>
 *   <li>Messages without a run id are delivered directly and are not journaled. They are not part
 *       of any StateVerdict run.</li>
 *   <li>Interventions are scheduled per run and topic, and each one applies to the <em>next</em>
 *       matching message, once, in the order they were scheduled. A message with no pending
 *       intervention is delivered normally (and journaled).</li>
 *   <li>Duplicate copies are delivered sequentially on the consumer's thread, so the second copy
 *       starts only after the first has finished. This makes "same event delivered twice" exactly
 *       reproducible; it does not model two copies racing each other.</li>
 *   <li>Handler failures are recorded in the journal as {@code FAILED} and are <em>not</em>
 *       rethrown. In test mode the journal is the evidence; letting the broker redeliver would
 *       introduce deliveries StateVerdict did not ask for.</li>
 *   <li>Held messages live in memory. If the service restarts, they are lost. A reset discards them
 *       without delivering them and reports how many it discarded.</li>
 * </ul>
 *
 * <p>Thread-safe. State changes happen under one lock; handlers always run outside it, so a slow
 * handler never blocks scheduling, releases or journal reads.
 */
public final class ControlledDispatcher implements Dispatcher, AutoCloseable {

    private record Rule(String topic, Intervention intervention) {
    }

    private record Held<T>(UUID token, InboundMessage<T> message, MessageHandler<T> handler,
                           Instant heldAt, Instant autoRelease, ScheduledFuture<?> timer) {

        Held<T> withTimer(ScheduledFuture<?> newTimer) {
            return new Held<>(token, message, handler, heldAt, autoRelease, newTimer);
        }

        HeldMessage view() {
            return new HeldMessage(token, message.runId(), message.topic(), message.eventKey(), heldAt, autoRelease);
        }
    }

    /** What a reset removed. */
    public record ResetSummary(int discardedMessages, int unusedInterventions) {
    }

    private final Object lock = new Object();
    private final Clock clock;
    private final ScheduledExecutorService timer;

    // All guarded by lock.
    private final Map<String, Deque<Rule>> rulesByRun = new HashMap<>();
    private final Map<String, LinkedHashMap<UUID, Held<?>>> heldByRun = new HashMap<>();
    private final Map<String, List<JournalEntry>> journalByRun = new HashMap<>();
    private final Map<String, Integer> attemptsByRunAndEvent = new HashMap<>();
    // Incremented by reset(). A delivery that started before a reset must not write into the
    // journal of the run's next use, so each delivery remembers the generation it started in.
    private final Map<String, Long> generationByRun = new HashMap<>();

    public ControlledDispatcher() {
        this(Clock.systemUTC());
    }

    public ControlledDispatcher(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "stateverdict-dispatch-timer");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Arms {@code intervention} for the next message on {@code topic} in run {@code runId}. */
    public void schedule(String runId, String topic, Intervention intervention) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(intervention, "intervention");
        synchronized (lock) {
            rulesByRun.computeIfAbsent(runId, id -> new ArrayDeque<>()).addLast(new Rule(topic, intervention));
        }
    }

    @Override
    public <T> void dispatch(InboundMessage<T> message, MessageHandler<T> handler) throws Exception {
        if (message.runId() == null) {
            handler.handle(message.payload());
            return;
        }
        Intervention intervention = takeRule(message.runId(), message.topic());
        switch (intervention) {
            case null -> deliver(message, handler);
            case Intervention.Duplicate duplicate -> {
                for (int copy = 0; copy < duplicate.copies(); copy++) {
                    deliver(message, handler);
                }
            }
            case Intervention.Hold hold -> park(message, handler, null);
            case Intervention.Delay delay -> park(message, handler, delay);
        }
    }

    /**
     * Delivers a held message now, on the caller's thread.
     *
     * @return the delivery's journal entry, or empty if no such message is held (already released,
     *         auto-released by a delay, or discarded by a reset)
     */
    public Optional<JournalEntry> release(String runId, UUID token) {
        Held<?> held;
        synchronized (lock) {
            LinkedHashMap<UUID, Held<?>> runHeld = heldByRun.get(runId);
            held = runHeld == null ? null : runHeld.remove(token);
        }
        if (held == null) {
            return Optional.empty();
        }
        if (held.timer() != null) {
            held.timer().cancel(false);
        }
        return Optional.of(deliverHeld(held));
    }

    /** Releases every held message of a run, oldest first. */
    public List<JournalEntry> releaseAll(String runId) {
        List<JournalEntry> delivered = new ArrayList<>();
        for (HeldMessage message : held(runId)) {
            release(runId, message.token()).ifPresent(delivered::add);
        }
        return delivered;
    }

    public List<HeldMessage> held(String runId) {
        synchronized (lock) {
            LinkedHashMap<UUID, Held<?>> runHeld = heldByRun.get(runId);
            return runHeld == null ? List.of() : runHeld.values().stream().map(Held::view).toList();
        }
    }

    public List<JournalEntry> journal(String runId) {
        synchronized (lock) {
            return List.copyOf(journalByRun.getOrDefault(runId, List.of()));
        }
    }

    /**
     * Clears all state of a run: cancels pending delays, discards held messages without delivering
     * them, drops unused interventions, and forgets the journal and attempt counters.
     */
    public ResetSummary reset(String runId) {
        synchronized (lock) {
            LinkedHashMap<UUID, Held<?>> runHeld = heldByRun.remove(runId);
            int discarded = 0;
            if (runHeld != null) {
                for (Held<?> held : runHeld.values()) {
                    if (held.timer() != null) {
                        held.timer().cancel(false);
                    }
                    discarded++;
                }
            }
            Deque<Rule> rules = rulesByRun.remove(runId);
            journalByRun.remove(runId);
            generationByRun.merge(runId, 1L, Long::sum);
            attemptsByRunAndEvent.keySet().removeIf(key -> key.startsWith(runId + "|"));
            return new ResetSummary(discarded, rules == null ? 0 : rules.size());
        }
    }

    @Override
    public void close() {
        timer.shutdownNow();
    }

    private Intervention takeRule(String runId, String topic) {
        synchronized (lock) {
            Deque<Rule> rules = rulesByRun.get(runId);
            if (rules == null) {
                return null;
            }
            for (Iterator<Rule> it = rules.iterator(); it.hasNext(); ) {
                Rule rule = it.next();
                if (rule.topic().equals(topic)) {
                    it.remove();
                    return rule.intervention();
                }
            }
            return null;
        }
    }

    private <T> void park(InboundMessage<T> message, MessageHandler<T> handler, Intervention.Delay delay) {
        UUID token = UUID.randomUUID();
        Instant now = clock.instant();
        Instant autoRelease = delay == null ? null : now.plus(delay.delay());
        synchronized (lock) {
            heldByRun.computeIfAbsent(message.runId(), id -> new LinkedHashMap<>())
                    .put(token, new Held<>(token, message, handler, now, autoRelease, null));
            append(message, Action.HELD, null, null, null, token);
        }
        if (delay != null) {
            // Scheduled after the message is stored, so the timer can never fire before it exists.
            ScheduledFuture<?> future = timer.schedule(
                    () -> release(message.runId(), token), delay.delay().toNanos(), TimeUnit.NANOSECONDS);
            synchronized (lock) {
                LinkedHashMap<UUID, Held<?>> runHeld = heldByRun.get(message.runId());
                if (runHeld != null) {
                    runHeld.computeIfPresent(token, (id, held) -> held.withTimer(future));
                }
            }
        }
    }

    private <T> JournalEntry deliverHeld(Held<T> held) {
        return deliver(held.message(), held.handler());
    }

    /**
     * Runs the handler outside the lock, then journals the result. Never throws handler errors.
     * If the run was reset while the handler ran, the entry is returned but not journaled.
     */
    private <T> JournalEntry deliver(InboundMessage<T> message, MessageHandler<T> handler) {
        int attempt;
        long generation;
        synchronized (lock) {
            attempt = attemptsByRunAndEvent.merge(message.runId() + "|" + message.eventKey(), 1, Integer::sum);
            generation = generationByRun.getOrDefault(message.runId(), 0L);
        }
        Outcome outcome;
        String error = null;
        try {
            handler.handle(message.payload());
            outcome = Outcome.ACKNOWLEDGED;
        } catch (Exception e) {
            outcome = Outcome.FAILED;
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        synchronized (lock) {
            if (generationByRun.getOrDefault(message.runId(), 0L) != generation) {
                return new JournalEntry(UUID.randomUUID(), message.runId(), message.topic(), message.eventKey(),
                        Action.DELIVERED, attempt, outcome, error, clock.instant());
            }
            return append(message, Action.DELIVERED, attempt, outcome, error, UUID.randomUUID());
        }
    }

    /** Caller must hold the lock. */
    private JournalEntry append(InboundMessage<?> message, Action action, Integer attempt,
                                Outcome outcome, String error, UUID entryId) {
        JournalEntry entry = new JournalEntry(entryId, message.runId(), message.topic(), message.eventKey(),
                action, attempt, outcome, error, clock.instant());
        journalByRun.computeIfAbsent(message.runId(), id -> new ArrayList<>()).add(entry);
        return entry;
    }
}
