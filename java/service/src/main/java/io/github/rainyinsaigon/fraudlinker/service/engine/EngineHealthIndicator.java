package io.github.rainyinsaigon.fraudlinker.service.engine;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Reports DOWN while the linking state is not loaded, so a load balancer stops sending traffic. */
@Component("linkingEngine")
public class EngineHealthIndicator implements HealthIndicator {
    private final LinkerEngine engine;

    public EngineHealthIndicator(LinkerEngine engine) {
        this.engine = engine;
    }

    @Override
    public Health health() {
        return engine.isAvailable() ? Health.up().build() : Health.down().withDetail("reason", "state not loaded").build();
    }
}
