package io.github.ramossvitor.herald;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Boots the full context against a disposable Postgres: migrations apply,
 * Hibernate validates the schema, the security chain assembles.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class HeraldApplicationTests {

	@Test
	void contextLoads() {
	}

}
