package io.github.ramossvitor.herald.email.resend;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.ramossvitor.herald.email.resend.ResendDomainPayloads.DomainStatus;

class ResendDomainPayloadsTest {

	@Test
	void verifiedAndFailedMapDirectly() {
		assertThat(ResendDomainPayloads.status("{\"status\":\"verified\"}")).isEqualTo(DomainStatus.VERIFIED);
		assertThat(ResendDomainPayloads.status("{\"status\":\"failed\"}")).isEqualTo(DomainStatus.FAILED);
	}

	@Test
	void everyOtherStatusCountsAsPending() {
		// temporary_failure is the provider still retrying DNS, not a verdict.
		assertThat(ResendDomainPayloads.status("{\"status\":\"not_started\"}")).isEqualTo(DomainStatus.PENDING);
		assertThat(ResendDomainPayloads.status("{\"status\":\"pending\"}")).isEqualTo(DomainStatus.PENDING);
		assertThat(ResendDomainPayloads.status("{\"status\":\"temporary_failure\"}")).isEqualTo(DomainStatus.PENDING);
		assertThat(ResendDomainPayloads.status("not json at all")).isEqualTo(DomainStatus.PENDING);
		assertThat(ResendDomainPayloads.status(null)).isEqualTo(DomainStatus.PENDING);
	}

	@Test
	void idAndRecordsAreExtractedVerbatim() {
		String body = "{\"id\":\"dom_1\",\"status\":\"pending\","
				+ "\"records\":[{\"record\":\"DKIM\",\"name\":\"resend._domainkey\",\"value\":\"p=abc\"}]}";
		assertThat(ResendDomainPayloads.domainId(body)).isEqualTo("dom_1");
		assertThat(ResendDomainPayloads.records(body))
				.isEqualTo("[{\"record\":\"DKIM\",\"name\":\"resend._domainkey\",\"value\":\"p=abc\"}]");
		assertThat(ResendDomainPayloads.records("{\"id\":\"dom_1\"}")).isNull();
	}
}
