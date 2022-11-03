package org.example.restservice;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.sleuth.annotation.NewSpan;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Observability design Springboot with opentelemetry
 * Log once per request session and use MDC to build state
 * Tracing of internal services - super simple using @NewSpan and set span attributes
 * https://github.com/logfellow/logstash-logback-encoder
 * https://grafana.com/blog/2022/06/23/how-to-send-logs-to-grafana-loki-with-the-opentelemetry-collector-using-fluent-forward-and-filelog-receivers/
 * https://grafana.com/blog/2021/04/13/how-to-send-traces-to-grafana-clouds-tempo-service-with-opentelemetry-collector/
 * https://cloud.spring.io/spring-cloud-static/spring-cloud-sleuth/1.3.4.RELEASE/single/spring-cloud-sleuth.html
 * https://github.com/open-telemetry/opentelemetry-java/tree/main/sdk-extensions/autoconfigure
 *
 * @NewSpan needed to make the auto intstrumeted SpanProcessor to pick it up
 */

@RestController
public class GreetingController {

    private static final Logger logger = LoggerFactory.getLogger(GreetingController.class);
    private static final String TEMPLATE_1 = "Hello, %s!";
    private static final String TEMPLATE_2 = "Bonjure, %s!";
    private final AtomicLong counter = new AtomicLong();

    private final MeterRegistry registry;

    // Use constructor injection to get the MeterRegistry
    public GreetingController(MeterRegistry registry) {
        this.registry = registry;
    }

    @Autowired
    private BackendConfiguration backendConfiguration;

    @GetMapping("/greeting")
    public ResponseEntity<Greeting> greeting(@RequestParam(value = "name", defaultValue = "World") String name) {
        logger.debug("This should be filtered out");
        // Add metrics using micrometer metrics types
        registry.counter("greetings.total", "name", name).increment();

        counter.incrementAndGet();
        // MDC enable structure key=value on any log entries until removed - using slf4j
        MDC.put("greetingsId", String.valueOf(counter.get()));
        MDC.put("greetingsName", name);

        // Get the current Span (Otel) that was created by auto instrumentation in the http layer
        Span span = Span.current();
        // Set span key=values
        span.setAttribute("greetingId", String.valueOf(counter.get()));

        try {
            // 3 methods that do the simulated work
            doGreetings(name);
            if (!getBackend(counter.get())) {
                throw new IllegalAccessException("Backend request failed");
            }
            Greeting greeting = helloGreetings(name);
            // Write a log entry with previous set MDC - using slf4j
            // Avoid using dynamic message, any dynamic content should have been set by MDC's
            logger.info("Greetings success");
            return new ResponseEntity<>(greeting, HttpStatus.OK);
        } catch (IllegalAccessException | ArithmeticException | ClassNotFoundException | ResourceAccessException e) {
            logger.error("Greetings failed", e);
            return new ResponseEntity<>(new Greeting(0L, "No more greetings!"), HttpStatus.INTERNAL_SERVER_ERROR);
        } finally {
            // Clear all MDC sets
            MDC.clear();
        }
    }


    @NewSpan
    private void doGreetings(String name) throws IllegalAccessException, ArithmeticException {

        // Get a child span from the current span
        Span childSpan = Span.current();

        try (Scope notused = childSpan.makeCurrent()) {

            Thread.sleep(Double.valueOf(Math.random() * 100).longValue());

            childSpan.setAttribute("greetingsName", name);

            if (Math.random() > 0.94) {
                MDC.put("greetingStatus", "OUT_OFF_GREETINGS");
                registry.counter("greetings.error", "name", name).increment();

                childSpan.setAttribute("greetingsStatus", "OUT_OFF_GREETINGS");
                childSpan.setStatus(StatusCode.ERROR, "No greetings available");
                if (Math.random() > 0.30) {
                    throw new IllegalAccessException("No greetings available");
                } else {
                    throw new ArithmeticException("No greetings calculated");
                }
            }

        } catch (InterruptedException e) {
            // Not used
        } finally {
            MDC.put("greetingStatus", "SUCCESS");
            childSpan.setAttribute("greetingsStatus", MDC.get("greetingStatus"));
            childSpan.setStatus(StatusCode.OK, "All good");
        }
    }

    @NewSpan()
    private Greeting helloGreetings(String name) throws ClassNotFoundException {
        Span childSpan = Span.current();
        try {
            Thread.sleep(Double.valueOf(Math.random() * 50).longValue());
        } catch (InterruptedException e) {
        }

        Double rand = Math.random();
        if (rand < 0.01) {
            MDC.put("greetingsLanguage", "chines");
            childSpan.setAttribute("greetingsLanguage", MDC.get("greetingsLanguage"));
            throw new ClassNotFoundException("Langauge not supported");
        } else if (rand > 0.3) {
            MDC.put("greetingsLanguage", "english");
            childSpan.setAttribute("greetingsLanguage", MDC.get("greetingsLanguage"));
            return new Greeting(counter.get(), String.format(TEMPLATE_1, name));
        }

        MDC.put("greetingsLanguage", "france");
        childSpan.setAttribute("greetingsLanguage", MDC.get("greetingsLanguage"));
        return new Greeting(counter.get(), String.format(TEMPLATE_2, name));

    }

    private boolean getBackend(Long id) {
        if (!backendConfiguration.getEnable()) {
            // Do not use the backend
            return true;
        }
        final String uri = String.format("%s/backend?id=%s", backendConfiguration.getEndpoint(), id);
        RestTemplate restTemplate = new RestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Object> entity = new HttpEntity<Object>(headers);
        ResponseEntity<String> out = restTemplate.exchange(uri, HttpMethod.GET, entity, String.class);
        return !out.getBody().equals("Failed");
    }
}
