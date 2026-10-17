package dev.stateverdict.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import dev.stateverdict.dispatch.JournalEntry.Action;
import dev.stateverdict.dispatch.JournalEntry.Outcome;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ControlledDispatcherTest {

    private static final String TOPIC = "payment.requested";

    private final ControlledDispatcher dispatcher = new ControlledDispatcher();
    private final List<String> handled = new CopyOnWriteArrayList<>();
    private final MessageHandler<String> handler = handled::add;

    @AfterEach
    void close() {
        dispatcher.close();
    }

    private static InboundMessage<String> message(String runId, String eventKey) {
        return new InboundMessage<>(TOPIC, runId, eventKey, "payload-" + eventKey);
    }

    @Nested
    class WithoutInterventions {

        @Test
        void messageOutsideAnyRunIsDeliveredOnceAndNotJournaled() throws Exception {
            dispatcher.dispatch(message(null, "e1"), handler);

            assertThat(handled).containsExactly("payload-e1");
        }

        @Test
        void messageInARunIsDeliveredOnceAndJournaled() throws Exception {
            dispatcher.dispatch(message("run-1", "e1"), handler);

            assertThat(handled).containsExactly("payload-e1");
            assertThat(dispatcher.journal("run-1"))
                    .singleElement()
                    .satisfies(entry -> {
                        assertThat(entry.action()).isEqualTo(Action.DELIVERED);
                        assertThat(entry.attempt()).isEqualTo(1);
                        assertThat(entry.outcome()).isEqualTo(Outcome.ACKNOWLEDGED);
                        assertThat(entry.eventKey()).isEqualTo("e1");
                    });
        }
    }

    @Nested
    class Duplicate {

        @Test
        void deliversTheSameEventTheRequestedNumberOfTimes() throws Exception {
            dispatcher.schedule("run-1", TOPIC, new Intervention.Duplicate(3));

            dispatcher.dispatch(message("run-1", "e1"), handler);

            assertThat(handled).containsExactly("payload-e1", "payload-e1", "payload-e1");
            assertThat(dispatcher.journal("run-1"))
                    .extracting(JournalEntry::eventKey, JournalEntry::attempt)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("e1", 1),
                            org.assertj.core.groups.Tuple.tuple("e1", 2),
                            org.assertj.core.groups.Tuple.tuple("e1", 3));
        }

        @Test
        void eachDeliveryGetsItsOwnDeliveryId() throws Exception {
            dispatcher.schedule("run-1", TOPIC, new Intervention.Duplicate(2));

            dispatcher.dispatch(message("run-1", "e1"), handler);

            assertThat(dispatcher.journal("run-1")).extracting(JournalEntry::entryId).doesNotHaveDuplicates();
        }

        @Test
        void appliesOnlyToTheNextMatchingMessage() throws Exception {
            dispatcher.schedule("run-1", TOPIC, new Intervention.Duplicate(2));

            dispatcher.dispatch(message("run-1", "e1"), handler);
            dispatcher.dispatch(message("run-1", "e2"), handler);

            assertThat(handled).containsExactly("payload-e1", "payload-e1", "payload-e2");
        }

        @Test
        void fewerThanTwoCopiesIsRejected() {
            assertThatThrownBy(() -> new Intervention.Duplicate(1)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class ConcurrentDuplicate {

        @Test
        void copiesRunAtTheSameTime() throws Exception {
            // Each copy waits until all three are inside the handler. Sequential delivery could
            // never get past the first copy's wait, so reaching three proves the handlers overlap.
            CountDownLatch inside = new CountDownLatch(3);
            List<Boolean> overlapped = new CopyOnWriteArrayList<>();
            List<String> threads = new CopyOnWriteArrayList<>();
            MessageHandler<String> waitsForTheOthers = payload -> {
                threads.add(Thread.currentThread().getName());
                inside.countDown();
                overlapped.add(inside.await(5, TimeUnit.SECONDS));
            };
            dispatcher.schedule("run-1", TOPIC, new Intervention.ConcurrentDuplicate(3));

            dispatcher.dispatch(message("run-1", "e1"), waitsForTheOthers);

            assertThat(overlapped).containsExactly(true, true, true);
            assertThat(threads).doesNotHaveDuplicates().doesNotContain(Thread.currentThread().getName());
        }

        @Test
        void dispatchReturnsOnlyWhenEveryCopyHasFinishedAndJournalsEachAttempt() throws Exception {
            MessageHandler<String> slow = payload -> {
                Thread.sleep(100);
                handled.add(payload);
            };
            dispatcher.schedule("run-1", TOPIC, new Intervention.ConcurrentDuplicate(2));

            dispatcher.dispatch(message("run-1", "e1"), slow);

            assertThat(handled).containsExactly("payload-e1", "payload-e1");
            assertThat(dispatcher.journal("run-1"))
                    .extracting(JournalEntry::attempt)
                    .containsExactlyInAnyOrder(1, 2);
            assertThat(dispatcher.journal("run-1"))
                    .extracting(JournalEntry::entryId).doesNotHaveDuplicates();
        }

        @Test
        void aFailingCopyIsJournaledAndTheOtherStillRuns() throws Exception {
            java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
            MessageHandler<String> oneFails = payload -> {
                if (calls.incrementAndGet() == 1) {
                    throw new IllegalStateException("conflict");
                }
            };
            dispatcher.schedule("run-1", TOPIC, new Intervention.ConcurrentDuplicate(2));

            dispatcher.dispatch(message("run-1", "e1"), oneFails);

            assertThat(dispatcher.journal("run-1"))
                    .extracting(JournalEntry::outcome)
                    .containsExactlyInAnyOrder(Outcome.FAILED, Outcome.ACKNOWLEDGED);
        }

        @Test
        void appliesOnlyToTheNextMatchingMessage() throws Exception {
            dispatcher.schedule("run-1", TOPIC, new Intervention.ConcurrentDuplicate(2));

            dispatcher.dispatch(message("run-1", "e1"), handler);
            dispatcher.dispatch(message("run-1", "e2"), handler);

            assertThat(handled).containsExactlyInAnyOrder("payload-e1", "payload-e1", "payload-e2");
        }

        @Test
        void fewerThanTwoCopiesIsRejected() {
            assertThatThrownBy(() -> new Intervention.ConcurrentDuplicate(1)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class Hold {

        @Test
        void parksTheMessageUntilReleased() throws Exception {
            dispatcher.schedule("run-1", TOPIC, new Intervention.Hold());

            dispatcher.dispatch(message("run-1", "e1"), handler);

            assertThat(handled).isEmpty();
            assertThat(dispatcher.held("run-1")).singleElement()
                    .satisfies(held -> assertThat(held.autoRelease()).isNull());
            assertThat(dispatcher.journal("run-1")).extracting(JournalEntry::action).containsExactly(Action.HELD);

            UUID token = dispatcher.held("run-1").getFirst().token();
            assertThat(dispatcher.release("run-1", token)).hasValueSatisfying(entry -> {
                assertThat(entry.action()).isEqualTo(Action.DELIVERED);
                assertThat(entry.attempt()).isEqualTo(1);
            });
            assertThat(handled).containsExactly("payload-e1");
            assertThat(dispatcher.held("run-1")).isEmpty();
        }

        @Test
        void releasingTwiceDeliversOnlyOnce() throws Exception {
            dispatcher.schedule("run-1", TOPIC, new Intervention.Hold());
            dispatcher.dispatch(message("run-1", "e1"), handler);
            UUID token = dispatcher.held("run-1").getFirst().token();

            dispatcher.release("run-1", token);

            assertThat(dispatcher.release("run-1", token)).isEmpty();
            assertThat(handled).hasSize(1);
        }

        @Test
        void heldMessagesCanBeReleasedInADifferentOrderThanTheyArrived() throws Exception {
            dispatcher.schedule("run-1", TOPIC, new Intervention.Hold());
            dispatcher.schedule("run-1", TOPIC, new Intervention.Hold());
            dispatcher.dispatch(message("run-1", "first"), handler);
            dispatcher.dispatch(message("run-1", "second"), handler);
            List<HeldMessage> held = dispatcher.held("run-1");

            dispatcher.release("run-1", held.get(1).token());
            dispatcher.release("run-1", held.get(0).token());

            assertThat(handled).containsExactly("payload-second", "payload-first");
        }

        @Test
        void releaseAllDeliversOldestFirst() throws Exception {
            dispatcher.schedule("run-1", TOPIC, new Intervention.Hold());
            dispatcher.schedule("run-1", TOPIC, new Intervention.Hold());
            dispatcher.dispatch(message("run-1", "first"), handler);
            dispatcher.dispatch(message("run-1", "second"), handler);

            assertThat(dispatcher.releaseAll("run-1")).hasSize(2);
            assertThat(handled).containsExactly("payload-first", "payload-second");
        }
    }

    @Nested
    class Delay {

        @Test
        void releasesAutomaticallyAfterTheDelay() throws Exception {
            dispatcher.schedule("run-1", TOPIC, new Intervention.Delay(Duration.ofMillis(200)));

            dispatcher.dispatch(message("run-1", "e1"), handler);

            assertThat(handled).isEmpty();
            assertThat(dispatcher.held("run-1")).singleElement()
                    .satisfies(held -> assertThat(held.autoRelease()).isNotNull());
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(handled).containsExactly("payload-e1"));
            assertThat(dispatcher.held("run-1")).isEmpty();
            assertThat(dispatcher.journal("run-1"))
                    .extracting(JournalEntry::action)
                    .containsExactly(Action.HELD, Action.DELIVERED);
        }

        @Test
        void canBeReleasedEarlyAndIsThenNotDeliveredAgain() throws Exception {
            dispatcher.schedule("run-1", TOPIC, new Intervention.Delay(Duration.ofMillis(300)));
            dispatcher.dispatch(message("run-1", "e1"), handler);

            dispatcher.release("run-1", dispatcher.held("run-1").getFirst().token());
            Thread.sleep(600);

            assertThat(handled).containsExactly("payload-e1");
        }

        @Test
        void nonPositiveDelayIsRejected() {
            assertThatThrownBy(() -> new Intervention.Delay(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class Matching {

        @Test
        void interventionForAnotherTopicDoesNotApply() throws Exception {
            dispatcher.schedule("run-1", "some.other.topic", new Intervention.Hold());

            dispatcher.dispatch(message("run-1", "e1"), handler);

            assertThat(handled).containsExactly("payload-e1");
        }

        @Test
        void interventionForAnotherRunDoesNotApply() throws Exception {
            dispatcher.schedule("run-2", TOPIC, new Intervention.Hold());

            dispatcher.dispatch(message("run-1", "e1"), handler);

            assertThat(handled).containsExactly("payload-e1");
            assertThat(dispatcher.journal("run-2")).isEmpty();
        }

        @Test
        void interventionsApplyInTheOrderTheyWereScheduled() throws Exception {
            dispatcher.schedule("run-1", TOPIC, new Intervention.Hold());
            dispatcher.schedule("run-1", TOPIC, new Intervention.Duplicate(2));

            dispatcher.dispatch(message("run-1", "e1"), handler);
            dispatcher.dispatch(message("run-1", "e2"), handler);

            assertThat(handled).containsExactly("payload-e2", "payload-e2");
            assertThat(dispatcher.held("run-1")).extracting(HeldMessage::eventKey).containsExactly("e1");
        }
    }

    @Nested
    class Failures {

        @Test
        void handlerFailureIsJournaledAndNotRethrown() throws Exception {
            MessageHandler<String> failing = payload -> {
                throw new IllegalStateException("database unavailable");
            };

            dispatcher.dispatch(message("run-1", "e1"), failing);

            assertThat(dispatcher.journal("run-1")).singleElement().satisfies(entry -> {
                assertThat(entry.outcome()).isEqualTo(Outcome.FAILED);
                assertThat(entry.error()).contains("database unavailable");
            });
        }

        @Test
        void aFailedFirstCopyDoesNotStopTheSecond() throws Exception {
            List<String> seen = new CopyOnWriteArrayList<>();
            MessageHandler<String> failsOnce = payload -> {
                seen.add(payload);
                if (seen.size() == 1) {
                    throw new IllegalStateException("transient");
                }
            };
            dispatcher.schedule("run-1", TOPIC, new Intervention.Duplicate(2));

            dispatcher.dispatch(message("run-1", "e1"), failsOnce);

            assertThat(dispatcher.journal("run-1"))
                    .extracting(JournalEntry::outcome)
                    .containsExactly(Outcome.FAILED, Outcome.ACKNOWLEDGED);
        }
    }

    @Nested
    class Reset {

        @Test
        void discardsHeldMessagesWithoutDeliveringAndCancelsDelays() throws Exception {
            dispatcher.schedule("run-1", TOPIC, new Intervention.Hold());
            dispatcher.schedule("run-1", TOPIC, new Intervention.Delay(Duration.ofMillis(200)));
            dispatcher.schedule("run-1", TOPIC, new Intervention.Duplicate(2));
            dispatcher.dispatch(message("run-1", "e1"), handler);
            dispatcher.dispatch(message("run-1", "e2"), handler);

            ControlledDispatcher.ResetSummary summary = dispatcher.reset("run-1");
            Thread.sleep(500);

            assertThat(summary.discardedMessages()).isEqualTo(2);
            assertThat(summary.unusedInterventions()).isEqualTo(1);
            assertThat(handled).isEmpty();
            assertThat(dispatcher.held("run-1")).isEmpty();
            assertThat(dispatcher.journal("run-1")).isEmpty();
        }

        @Test
        void restartsAttemptNumbering() throws Exception {
            dispatcher.dispatch(message("run-1", "e1"), handler);
            dispatcher.reset("run-1");

            dispatcher.dispatch(message("run-1", "e1"), handler);

            assertThat(dispatcher.journal("run-1")).singleElement()
                    .satisfies(entry -> assertThat(entry.attempt()).isEqualTo(1));
        }

        @Test
        void deliveryStillRunningDuringResetDoesNotLeakIntoTheNextUseOfTheRun() throws Exception {
            CountDownLatch handlerStarted = new CountDownLatch(1);
            CountDownLatch finishHandler = new CountDownLatch(1);
            MessageHandler<String> slow = payload -> {
                handlerStarted.countDown();
                finishHandler.await();
            };
            Thread consumer = new Thread(() -> {
                try {
                    dispatcher.dispatch(message("run-1", "e1"), slow);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            consumer.start();
            assertThat(handlerStarted.await(5, TimeUnit.SECONDS)).isTrue();

            dispatcher.reset("run-1");
            finishHandler.countDown();
            consumer.join(5_000);

            assertThat(dispatcher.journal("run-1")).isEmpty();
            dispatcher.dispatch(message("run-1", "e1"), handler);
            assertThat(dispatcher.journal("run-1")).singleElement()
                    .satisfies(entry -> assertThat(entry.attempt()).isEqualTo(1));
        }

        @Test
        void leavesOtherRunsUntouched() throws Exception {
            dispatcher.schedule("run-2", TOPIC, new Intervention.Hold());
            dispatcher.dispatch(message("run-2", "e1"), handler);

            dispatcher.reset("run-1");

            assertThat(dispatcher.held("run-2")).hasSize(1);
        }
    }

    @Test
    void directDispatcherAlwaysDeliversOnce() throws Exception {
        new DirectDispatcher().dispatch(message("run-1", "e1"), handler);

        assertThat(handled).containsExactly("payload-e1");
    }
}
