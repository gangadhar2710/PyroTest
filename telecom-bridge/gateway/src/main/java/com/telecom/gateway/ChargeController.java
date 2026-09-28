package com.telecom.gateway;

import static com.telecom.diameter.DiameterConstants.RESULT_SUCCESS;

import com.telecom.diameter.DiameterMessage;
import com.telecom.diameter.Messages;
import jakarta.validation.Valid;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ChargeController {

    private static final Logger log = LoggerFactory.getLogger(ChargeController.class);

    public record ErrorBody(String error, String message) {}

    private final DiameterClient client;

    public ChargeController(DiameterClient client) {
        this.client = client;
    }

    @PostMapping("/charge")
    public CompletableFuture<ResponseEntity<Object>> charge(@Valid @RequestBody ChargeRequest request) {
        long start = System.nanoTime();
        return client.sendCcr(request.msisdn(), request.requestedUnits())
                .handle((cca, error) -> error == null
                        ? success(cca, start)
                        : failure(unwrap(error), request));
    }

    private ResponseEntity<Object> success(DiameterMessage cca, long startNanos) {
        long rc = cca.resultCode();
        boolean ok = rc == RESULT_SUCCESS;
        ChargeResponse body = new ChargeResponse(Messages.sessionId(cca), rc, ok ? "SUCCESS" : "REJECTED",
                Messages.grantedOctets(cca), (System.nanoTime() - startNanos) / 1_000_000);
        return ResponseEntity.status(ok ? HttpStatus.OK : HttpStatus.BAD_GATEWAY).body(body);
    }

    private ResponseEntity<Object> failure(Throwable t, ChargeRequest req) {
        if (t instanceof TimeoutException) {
            log.warn("Diameter timeout for msisdn={}", req.msisdn());
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                    .body(new ErrorBody("GATEWAY_TIMEOUT", "No CCA received from Diameter peer in time"));
        }
        if (t instanceof GatewayUnavailableException) {
            log.warn("Diameter unavailable: {}", t.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ErrorBody("SERVICE_UNAVAILABLE", t.getMessage()));
        }
        log.error("Unexpected error", t);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorBody("INTERNAL_ERROR", "Unexpected error"));
    }

    private static Throwable unwrap(Throwable t) {
        return (t instanceof CompletionException && t.getCause() != null) ? t.getCause() : t;
    }
}
