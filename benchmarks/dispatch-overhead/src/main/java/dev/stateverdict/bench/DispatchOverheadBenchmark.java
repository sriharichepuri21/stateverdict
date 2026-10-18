package dev.stateverdict.bench;

import dev.stateverdict.dispatch.ControlledDispatcher;
import dev.stateverdict.dispatch.DirectDispatcher;
import dev.stateverdict.dispatch.Dispatcher;
import dev.stateverdict.dispatch.InboundMessage;
import dev.stateverdict.dispatch.MessageHandler;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * What it costs a consumer, per message, to route a message through StateVerdict's dispatcher
 * with no fault armed, compared with calling the business handler directly.
 *
 * <ul>
 *   <li>{@code baseline}: the handler is called directly, as in an application without the adapter.</li>
 *   <li>{@code direct}: what the adapter does in production mode. It builds the message envelope
 *       (topic, run id, event key, as the external target's adapter does) and passes it through
 *       {@link DirectDispatcher}.</li>
 *   <li>{@code controlled}: the same through {@link ControlledDispatcher}, the test mode, with no
 *       fault armed. Every message belongs to a new run, as every order does in the external
 *       target, so this includes looking up rules and writing the journal entry.</li>
 * </ul>
 *
 * The handler only consumes the payload, so the numbers isolate the dispatcher. A real handler
 * (database reads and writes, a Kafka send) takes milliseconds; the end-to-end benchmark
 * ({@code targets/piomin-saga/overhead.sh}) measures that.
 *
 * <p>Single-threaded, like a Kafka listener on one partition. The controlled dispatcher is
 * recreated every iteration, because its journal grows with every run until a run is reset.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 3, jvmArgsAppend = {"-Xms2g", "-Xmx2g"})
public class DispatchOverheadBenchmark {

    /** Stands in for the target's order: an id and a status the adapter reads. */
    public record Order(long id, String status) {
    }

    private final Dispatcher direct = new DirectDispatcher();
    private ControlledDispatcher controlled;
    private long nextId;
    private Blackhole blackhole;
    private MessageHandler<Order> handler;

    @Setup(Level.Iteration)
    public void setUp(Blackhole blackhole) {
        this.blackhole = blackhole;
        this.handler = blackhole::consume;
        this.controlled = new ControlledDispatcher();
    }

    @TearDown(Level.Iteration)
    public void tearDown() {
        controlled.close();
    }

    private Order next() {
        long id = ++nextId;
        return new Order(id, (id & 1) == 0 ? "NEW" : "CONFIRMED");
    }

    /** The same envelope the external target's adapter builds for every consumed order. */
    private static InboundMessage<Order> envelope(Order o) {
        String phase = o.status().equals("NEW") ? "orders.reserve" : "orders.settle";
        return new InboundMessage<>(phase, "order-" + o.id(), phase + ":" + o.id(), o);
    }

    @Benchmark
    public void baseline() throws Exception {
        handler.handle(next());
    }

    @Benchmark
    public void direct() throws Exception {
        direct.dispatch(envelope(next()), handler);
    }

    @Benchmark
    public void controlled() throws Exception {
        controlled.dispatch(envelope(next()), handler);
    }
}
