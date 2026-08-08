package com.photoapp.feign;

import com.photoapp.feign.client.AccountFeignClient;
import com.photoapp.feign.client.AlbumFeignClient;
import com.photoapp.feign.client.PhotoFeignClient;
import com.photoapp.feign.client.UserFeignClient;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * The context root for every Phase 4 test.
 *
 * <p>{@code photo-app-feign-lib} is a library and has no application class of its own, so this
 * supplies one. It is deliberately minimal — {@code @EnableAutoConfiguration} plus the four
 * clients — because everything under test must come from the production classes themselves:
 * {@link com.photoapp.feign.configuration.FeignConfiguration} is picked up from each client's
 * own {@code configuration} attribute, and the Resilience4j aspects from
 * {@code resilience4j-spring-boot3}'s auto-configuration.
 *
 * <p>Listing the clients explicitly rather than scanning is what keeps the four suites honest: a
 * new {@code @FeignClient} interface added to the library is invisible here until someone adds it,
 * and {@link FeignClientInventoryTest} turns that omission into a failure.
 *
 * <p>It lives in the root test package rather than beside the harness because
 * {@code @SpringBootTest} searches for a {@code @SpringBootConfiguration} by walking <em>upwards</em>
 * from the test's own package. From {@code com.photoapp.feign.client} a sibling
 * {@code …feign.harness} is never seen.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@EnableFeignClients(clients = {
        UserFeignClient.class,
        AccountFeignClient.class,
        AlbumFeignClient.class,
        PhotoFeignClient.class
})
public class FeignTestApplication {
}
