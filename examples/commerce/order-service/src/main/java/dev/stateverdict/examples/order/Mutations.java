package dev.stateverdict.examples.order;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * <b>Evaluation only.</b> Switches on deliberately broken (or deliberately harmless) variants of
 * this service, so StateVerdict and the conventional test suite can be compared on the same
 * defects. Set with {@code evaluation.mutation=M03,C02} (or {@code EVALUATION_MUTATION}); empty by
 * default. The corpus is documented in {@code docs/evaluation/corpus.md}.
 */
@Component
public class Mutations {

    private static final Logger log = LoggerFactory.getLogger(Mutations.class);

    private final Set<String> active;

    public Mutations(@Value("${evaluation.mutation:}") String spec) {
        this.active = Arrays.stream(spec.split(","))
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        if (!active.isEmpty()) {
            log.warn("EVALUATION MUTATIONS ACTIVE: {}. This service is deliberately altered.", active);
        }
    }

    public boolean active(String id) {
        return active.contains(id);
    }
}
