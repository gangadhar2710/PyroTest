package com.telecom.gateway;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;


public record ChargeRequest(
        @NotBlank @Pattern(regexp = "\\d{7,15}", message = "msisdn must be 7-15 digits (E.164 without '+')") String msisdn,
        @NotNull @Positive Long requestedUnits) {
}
