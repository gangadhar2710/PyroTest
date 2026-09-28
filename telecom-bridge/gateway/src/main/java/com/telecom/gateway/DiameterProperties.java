package com.telecom.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "diameter")
public record DiameterProperties(
        String host,
        int port,
        String originHost,
        String originRealm,
        String destinationRealm,
        String serviceContextId,
        int connectTimeoutMs,
        long requestTimeoutMs,
        int watchdogSeconds,
        long reconnectDelayMs) {
}
