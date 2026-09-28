package com.telecom.gateway;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class DiameterHealthIndicator implements HealthIndicator {
    private final DiameterClient client;

    public DiameterHealthIndicator(DiameterClient client) {
        this.client = client;
    }

    @Override
    public Health health() {
        Health.Builder b = client.isReady() ? Health.up() : Health.down();
        return b.withDetail("diameterReady", client.isReady()).withDetail("inFlight", client.inFlight()).build();
    }
}
