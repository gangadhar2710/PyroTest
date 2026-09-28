package com.telecom.gateway;

public record ChargeResponse(String sessionId, long resultCode, String status, long grantedUnits, long latencyMs) {
}
