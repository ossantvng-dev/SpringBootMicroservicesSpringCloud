package com.photoapp.feign.harness;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Shared harness for the Feign and resilience suites.
 *
 * <p>The stub sits at the <em>HTTP boundary</em>, not at the Feign interface. That is the whole
 * design of this phase: {@code CustomFeignErrorDecoder}, {@code FeignFallbacks},
 * {@code DownstreamFailurePredicate} and the two Resilience4j aspects all live <em>below</em> the
 * interface, so a Mockito mock of {@code UserFeignClient} would exercise none of them and every
 * assertion in Phase 4 would pass vacuously. WireMock speaks real HTTP over a real socket, so the
 * request travels the same path it does in production, minus Eureka.
 *
 * <p>One server serves all four downstream service ids. They are distinguished by path
 * ({@code /users/…}, {@code /accounts/…}, …) exactly as the real services are, and using one
 * server keeps the request journal in a single place so retry-count assertions can simply ask how
 * many times the endpoint was hit.
 */
/*
    WebEnvironment.MOCK, not NONE, and it is load-bearing rather than incidental. Spring Cloud
    OpenFeign builds its Encoder and Decoder from the HttpMessageConverters bean; that bean is
    contributed by the web auto-configuration, which does not apply when the application type is
    none. With NONE the context fails outright:
    "No bean found of type interface feign.codec.Encoder for photo-app-users-service".
    MOCK gives a WebApplicationContext without binding a port, so the converters exist and the
    request/response bodies are serialised by exactly the converters production uses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
public abstract class AbstractFeignClientTest {

    /** The four downstream service ids, matching the {@code name} on each {@code @FeignClient}. */
    private static final List<String> SERVICE_IDS = List.of(
            "photo-app-users-service",
            "photo-app-accounts-service",
            "photo-app-albums-service",
            "photo-app-photos-service"
    );

    /*
        Started from a static initialiser rather than @BeforeAll on purpose. Spring loads the
        application context inside SpringExtension's own beforeAll callback, which runs BEFORE the
        test class's @BeforeAll - and @DynamicPropertySource suppliers are evaluated during that
        context load. A @BeforeAll would therefore hand out a port of 0.
     */
    protected static final WireMockServer DOWNSTREAM = startDownstream();

    private static WireMockServer startDownstream() {
        WireMockServer server = new WireMockServer(options().dynamicPort());
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        return server;
    }

    /**
     * Points every service id at WireMock through {@code SimpleDiscoveryClient}, which stands in
     * for Eureka. Feign still resolves the id through the load balancer, so the client is targeted
     * the same way it is in production — the discovery mechanism is the only substitution.
     */
    @DynamicPropertySource
    static void registerDownstreamInstances(DynamicPropertyRegistry registry) {
        for (String serviceId : SERVICE_IDS) {
            registry.add(
                    "spring.cloud.discovery.client.simple.instances." + serviceId + "[0].uri",
                    () -> "http://localhost:" + DOWNSTREAM.port()
            );
        }
    }

    @Autowired
    protected CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    protected RetryRegistry retryRegistry;

    /**
     * Resets stubs, the request journal and — critically — every circuit breaker.
     *
     * <p>The registry is a singleton shared across the whole context, and the context is cached
     * across test classes by the Spring TestContext framework. Without this reset a breaker opened
     * by a failure test stays open and fails the next class's success-path test, in an order that
     * depends on how JUnit happens to sequence the suite.
     */
    @BeforeEach
    void resetDownstreamAndBreakers() {
        DOWNSTREAM.resetAll();
        WireMock.configureFor("localhost", DOWNSTREAM.port());
        circuitBreakerRegistry.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }

    /** The breaker for a given {@code @CircuitBreaker(name = …)}, for state assertions. */
    protected CircuitBreaker breaker(String name) {
        return circuitBreakerRegistry.circuitBreaker(name);
    }
}
