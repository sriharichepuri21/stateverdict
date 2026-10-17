package dev.stateverdict.core.saga;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stateverdict.core.schedule.Fault;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SagaScheduleTest {

    private static final SagaSchedule.Order ORDER = new SagaSchedule.Order(100, 1, 1000, 10);

    private static SagaSchedule.Arm arm(SagaSchedule.Service service, String phase, Fault fault) {
        return new SagaSchedule.Arm(service, phase, fault);
    }

    @Test
    void aPlainOrderWithValidFaultsIsValid() {
        SagaSchedule schedule = new SagaSchedule("s", ORDER, List.of(
                arm(SagaSchedule.Service.PAYMENT, SagaSchedule.RESERVE, Fault.duplicate(2)),
                arm(SagaSchedule.Service.STOCK, SagaSchedule.SETTLE, Fault.delay(500))));
        assertThat(schedule.problems()).isEmpty();
        assertThat(new SagaSchedule("s", ORDER, null).arms()).isEmpty();
    }

    @Test
    void invalidSchedulesAreExplained() {
        assertThat(new SagaSchedule("s", new SagaSchedule.Order(0, 1, 1, 1), List.of()).problems())
                .anyMatch(p -> p.contains("positive"));
        assertThat(new SagaSchedule("s", ORDER, List.of(arm(SagaSchedule.Service.PAYMENT, "orders", Fault.duplicate(2))))
                .problems()).anyMatch(p -> p.contains("phase must be"));
        assertThat(new SagaSchedule("s", ORDER, List.of(arm(SagaSchedule.Service.PAYMENT, SagaSchedule.RESERVE, Fault.hold())))
                .problems()).anyMatch(p -> p.contains("HOLD is not supported"));
        assertThat(new SagaSchedule("s", ORDER, List.of(
                arm(SagaSchedule.Service.PAYMENT, SagaSchedule.RESERVE, Fault.duplicate(2)),
                arm(SagaSchedule.Service.PAYMENT, SagaSchedule.RESERVE, Fault.delay(100)))).problems())
                .anyMatch(p -> p.contains("at most one fault"));
    }

    @Test
    void everyScheduleShippedForTheTargetParsesAndIsValid() throws Exception {
        JsonMapper json = JsonMapper.builder().build();
        Path dir = Path.of("../../targets/piomin-saga/scenarios");
        List<Path> files;
        try (Stream<Path> listing = Files.list(dir)) {
            files = listing.filter(p -> p.toString().endsWith(".json")).toList();
        }
        assertThat(files).hasSizeGreaterThanOrEqualTo(8);
        for (Path file : files) {
            SagaSchedule schedule = json.readValue(Files.readString(file), SagaSchedule.class);
            assertThat(schedule.problems()).as(file.toString()).isEmpty();
            assertThat(file.getFileName().toString()).isEqualTo(schedule.name() + ".json");
        }
    }
}
