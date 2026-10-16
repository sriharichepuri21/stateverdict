package dev.stateverdict.examples.payment;

import dev.stateverdict.dispatch.ControlledDispatcher;
import dev.stateverdict.dispatch.DirectDispatcher;
import dev.stateverdict.dispatch.Dispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses how consumed messages reach the business handler.
 *
 * <ul>
 *   <li>{@code stateverdict.dispatch.mode=direct} (default): deliver every message once. Production.</li>
 *   <li>{@code stateverdict.dispatch.mode=controlled}: StateVerdict can hold, delay and duplicate
 *       deliveries through {@link DispatchControlController}. Test environments only.</li>
 * </ul>
 */
@Configuration
public class DispatchConfig {

    static final String MODE_PROPERTY = "stateverdict.dispatch.mode";

    private static final Logger log = LoggerFactory.getLogger(DispatchConfig.class);

    @Bean
    @ConditionalOnProperty(name = MODE_PROPERTY, havingValue = "direct", matchIfMissing = true)
    Dispatcher directDispatcher() {
        return new DirectDispatcher();
    }

    @Bean
    @ConditionalOnProperty(name = MODE_PROPERTY, havingValue = "controlled")
    ControlledDispatcher controlledDispatcher() {
        log.warn("StateVerdict CONTROLLED dispatch is enabled: deliveries can be held, delayed and duplicated "
                + "through /_stateverdict/dispatch. This must never be enabled in production.");
        return new ControlledDispatcher();
    }
}
