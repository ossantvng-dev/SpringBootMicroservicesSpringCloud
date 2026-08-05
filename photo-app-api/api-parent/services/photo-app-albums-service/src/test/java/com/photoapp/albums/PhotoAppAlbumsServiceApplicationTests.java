package com.photoapp.albums;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@Disabled("""
        Full-context startup needs a Config Server, MySQL, Eureka and RabbitMQ, none of which
        exist in a unit test - which is why this was silently disabled rather than fixed.
        Phase 7 of docs/plans/testing-plan.md replaces it with a *IT that supplies those
        dependencies via Testcontainers and WireMock. Until then it asserts nothing, and
        the reason is recorded here rather than left implicit.
        """)
@SpringBootTest
class PhotoAppAlbumsServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
