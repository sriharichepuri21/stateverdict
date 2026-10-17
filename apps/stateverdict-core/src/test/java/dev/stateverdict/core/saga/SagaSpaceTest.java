package dev.stateverdict.core.saga;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stateverdict.core.explore.ExplorationReport;
import dev.stateverdict.core.explore.Explorer;
import dev.stateverdict.core.explore.ScheduleSpace;
import dev.stateverdict.core.explore.ShrinkReport;
import dev.stateverdict.core.explore.Shrinker;
import dev.stateverdict.core.report.RunReport;
import dev.stateverdict.core.rules.RuleResult;
import dev.stateverdict.core.schedule.Fault;
import dev.stateverdict.core.verdict.EvaluationStatus;
import dev.stateverdict.core.verdict.ExecutionStatus;
import dev.stateverdict.core.verdict.Verdict;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SagaSpaceTest {

    private final SagaSpace space = new SagaSpace();

    private List<SagaSchedule> generate(long seed, int count) {
        ScheduleSpace.Generator<SagaSchedule> generator = space.generator(seed);
        return IntStream.range(0, count).mapToObj(i -> generator.next()).toList();
    }

    @Test
    void everyGeneratedScheduleIsValidAndTheSameSeedRepeatsExactly() {
        for (SagaSchedule schedule : generate(42, 1_000)) {
            assertThat(schedule.problems()).as(schedule.name()).isEmpty();
        }
        assertThat(generate(7, 50)).isEqualTo(generate(7, 50));
        assertThat(generate(7, 50)).isNotEqualTo(generate(8, 50));
    }

    @Test
    void theWholeSpaceIsReachable() {
        TreeMap<String, Integer> coverage = new TreeMap<>();
        generate(42, 500).forEach(schedule -> space.recordCoverage(schedule, coverage));
        Set<String> kinds = coverage.keySet();
        for (String target : List.of("payment orders.reserve", "stock orders.reserve", "payment orders.settle",
                "stock orders.settle")) {
            for (String fault : List.of("none", "DUPLICATE", "DUPLICATE_CONCURRENT", "DELAY <= 2 s", "DELAY > 10 s")) {
                assertThat(kinds).contains(target + " " + fault);
            }
        }
        assertThat(kinds).contains("order: affordable, in stock", "order: unaffordable", "order: short of stock",
                "order: unaffordable and short of stock");
    }

    @Test
    void reductionsRemoveFaultsThenFixTheOrderThenSimplify() {
        SagaSchedule schedule = new SagaSchedule("s", new SagaSchedule.Order(5_000, 20, 1_000, 10), List.of(
                new SagaSchedule.Arm(SagaSchedule.Service.PAYMENT, SagaSchedule.RESERVE, Fault.duplicateConcurrent(2)),
                new SagaSchedule.Arm(SagaSchedule.Service.STOCK, SagaSchedule.SETTLE, Fault.delay(12_000))));

        List<String> changes = space.reductions(schedule).stream().map(ScheduleSpace.Candidate::change).toList();

        assertThat(changes).containsExactly(
                "remove arm DUPLICATE_CONCURRENT x2 on orders.reserve (payment)",
                "remove arm DELAY 12000 ms on orders.settle (stock)",
                "make the order affordable",
                "put the product in stock",
                "simplify DUPLICATE_CONCURRENT x2 to DUPLICATE x2 on orders.reserve (payment)",
                "simplify DELAY 12000 ms to DELAY 6000 ms on orders.settle (stock)");
        assertThat(space.interventions(schedule)).isEqualTo(4);
        assertThat(space.reductions(new SagaSchedule("plain", new SagaSchedule.Order(100, 1, 1_000, 10), List.of())))
                .isEmpty();
    }

    @Test
    void theGenericEngineExploresAndShrinksSagaSchedules() throws Exception {
        // A fake target that fails exactly when the payment service rejects: the shape of the real
        // finding. The shrinker must strip every fault and keep only the unaffordable order.
        Predicate<SagaSchedule> fails = s -> s.order().price() >= s.order().funds();
        JsonMapper json = JsonMapper.builder().build();

        ExplorationReport<SagaSchedule> report = new Explorer<>(space, s -> report(s, fails.test(s)), json)
                .explore(42, 100, null, false, null);
        assertThat(report.findings()).isNotEmpty().allMatch(f -> fails.test(f.schedule()));
        SagaSchedule biggest = report.findings().stream().map(ExplorationReport.Finding::schedule)
                .max(java.util.Comparator.comparingInt(space::interventions)).orElseThrow();

        ShrinkReport<SagaSchedule> shrunk = new Shrinker<>(space, s -> report(s, fails.test(s)), 3, 5)
                .shrink(biggest, List.of(), null);

        assertThat(shrunk.status()).isEqualTo(ShrinkReport.Status.MINIMIZED);
        assertThat(shrunk.minimized().arms()).isEmpty();
        assertThat(shrunk.minimized().order().price()).isGreaterThanOrEqualTo(shrunk.minimized().order().funds());
        assertThat(shrunk.minimized().name()).endsWith("-min");
    }

    private static RunReport report(SagaSchedule schedule, boolean violates) {
        RuleResult result = new RuleResult("SAGA-2", 1, "d", violates ? EvaluationStatus.VIOLATED : EvaluationStatus.SATISFIED,
                "e", "o", "r", List.of());
        return new RunReport(RunReport.VERSION, "r", schedule.name(), 1, List.of(), ExecutionStatus.COMPLETED,
                List.of(result), violates ? Verdict.FAIL : Verdict.PASS, List.of(), List.of(), Instant.EPOCH, Instant.EPOCH);
    }

    @Test
    void namesAreKeptAndRenamed() {
        SagaSchedule schedule = generate(1, 1).getFirst();
        assertThat(space.renamed(schedule, "x").name()).isEqualTo("x");
        assertThat(space.renamed(schedule, "x").arms()).isEqualTo(schedule.arms());
        assertThat(new HashSet<>(space.steps(schedule))).isNotEmpty();
    }
}
