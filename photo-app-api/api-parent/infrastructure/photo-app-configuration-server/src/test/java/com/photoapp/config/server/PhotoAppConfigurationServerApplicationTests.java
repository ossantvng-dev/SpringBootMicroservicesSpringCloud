package com.photoapp.config.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/*
    This test used to pass only because of AMBIENT MACHINE STATE.

    application.properties for this service resolves ${CONFIG_SERVER_ADMIN_USER},
    ${CONFIG_SERVER_ADMIN_PASSWORD}, ${KEYSTORE_PASSWORD} and - under the default `git`
    profile - ${GIT_USERNAME} and ${GIT_TOKEN}. All are set as OS environment variables on the
    developer's machine, so the context started locally and would fail on any clean CI runner
    with a placeholder-resolution error naming a property rather than the real cause.

    Fixed by supplying every value the context needs as an explicit test property, and by
    running the `native` profile so the server reads configuration from the classpath instead
    of cloning a private GitHub repository over the network. It asserts the same thing it
    always claimed to - that the context starts - but now does so hermetically.
 */
@SpringBootTest(properties = {
        "spring.profiles.active=native",
        "spring.cloud.config.server.native.search-locations=classpath:/config-test",
        "CONFIG_SERVER_ADMIN_USER=test-admin",
        "CONFIG_SERVER_ADMIN_PASSWORD=test-password",
        "KEYSTORE_PASSWORD=test-keystore-password",
        "RABBITMQ_USER=guest",
        "RABBITMQ_PASSWORD=guest",
        // No keystore is bind-mounted in a test, and none is needed to prove the context starts.
        "encrypt.keyStore.location=",
        "spring.cloud.bus.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
class PhotoAppConfigurationServerApplicationTests {

    @Test
    void contextLoads() {
    }

}
