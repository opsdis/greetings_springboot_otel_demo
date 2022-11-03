package org.example.backendservice;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Observability design Springboot with opentelemetry
 */

@RestController
public class GreetingBackendController {

    private static final Logger logger = LoggerFactory.getLogger(GreetingBackendController.class);

    private final AtomicLong counter = new AtomicLong();

    private final MeterRegistry registry;

    // Use constructor injection to get the MeterRegistry
    public GreetingBackendController(MeterRegistry registry) {
        this.registry = registry;
    }

    @Autowired
    private Tracer tracer;

    @GetMapping("/backend")
    public ResponseEntity<String> greeting(@RequestParam(value = "id") Integer id) {
        // Add metrics using micrometer metrics types
        registry.counter("greetingsbackend.total").increment();

        //counter.incrementAndGet();
        // MDC enable structure key=value on any log entries until removed - using slf4j
        MDC.put("greetingsId", String.valueOf(id));


        // Get the current Span (Otel) that was created by auto instrumentation in the http layer
        Span span = Span.current();
        // Set span key=values
        span.setAttribute("greetingId", String.valueOf(id));
        try {
            Thread.sleep(Double.valueOf(Math.random() * 25).longValue());
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        try {
            if ((id % 10) == 1) {
                logger.warn("Bad request");
                registry.counter("greetingsbackend.error").increment();
                span.setStatus(StatusCode.ERROR, "Failed to process");
                return new ResponseEntity<>("Failed", HttpStatus.OK);
            }
            logger.info("Backend success");
            return new ResponseEntity<>("Success", HttpStatus.OK);
        } finally {
            // Clear all MDC sets
            MDC.clear();
        }
    }
}
