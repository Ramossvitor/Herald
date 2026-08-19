package io.github.ramossvitor.herald.sender;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class SenderIdentityVerifierTest {

	@Test
	void ladderEscalatesFromMinutesToHourly() {
		assertThat(SenderIdentityVerifier.nextDelay(1)).isEqualTo(Duration.ofMinutes(1));
		assertThat(SenderIdentityVerifier.nextDelay(4)).isEqualTo(Duration.ofMinutes(1));
		assertThat(SenderIdentityVerifier.nextDelay(5)).isEqualTo(Duration.ofMinutes(5));
		assertThat(SenderIdentityVerifier.nextDelay(10)).isEqualTo(Duration.ofMinutes(15));
		assertThat(SenderIdentityVerifier.nextDelay(15)).isEqualTo(Duration.ofHours(1));
		assertThat(SenderIdentityVerifier.nextDelay(79)).isEqualTo(Duration.ofHours(1));
	}
}
