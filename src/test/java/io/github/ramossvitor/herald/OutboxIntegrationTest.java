package io.github.ramossvitor.herald;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import io.github.ramossvitor.herald.email.EmailMessage;
import io.github.ramossvitor.herald.email.EmailMessageRepository;
import io.github.ramossvitor.herald.email.EmailStatus;
import io.github.ramossvitor.herald.email.EmailSubmissionService;
import io.github.ramossvitor.herald.email.SendEmailRequest;
import io.github.ramossvitor.herald.email.outbox.OutboxStore;
import io.github.ramossvitor.herald.email.outbox.OutboxWorker;
import io.github.ramossvitor.herald.tenant.Tenant;
import io.github.ramossvitor.herald.tenant.admin.TenantAdminService;

/**
 * The dispatch loop end to end against a WireMock provider: claim → send →
 * record, with every classification the retry policy distinguishes.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
		"herald.resend.api-key=re_test_fake",
		"herald.outbox.poll-interval=1h",
		"herald.outbox.send-interval=0ms",
		"herald.outbox.max-attempts=3",
})
class OutboxIntegrationTest {

	private static final WireMockServer RESEND = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
	private static final AtomicInteger SLUGS = new AtomicInteger();

	@DynamicPropertySource
	static void resendEndpoint(DynamicPropertyRegistry registry) {
		RESEND.start();
		registry.add("herald.resend.base-url", RESEND::baseUrl);
	}

	@AfterAll
	static void stopWireMock() {
		RESEND.stop();
	}

	@Autowired
	private TenantAdminService admin;

	@Autowired
	private EmailSubmissionService submissions;

	@Autowired
	private OutboxWorker worker;

	@Autowired
	private OutboxStore store;

	@Autowired
	private EmailMessageRepository messages;

	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	void resetProviderAndOutbox() {
		RESEND.resetAll();
		// Each test reasons about "everything due" — leftovers from other
		// tests would leak into runOnce() counts and claims.
		jdbc.update("delete from email_messages");
	}

	@Test
	void sendsAndRecordsProviderId() {
		RESEND.stubFor(post(urlEqualTo("/emails")).willReturn(okJson("{\"id\":\"re_123\"}")));
		UUID id = submit(newTenant(), "player@example.com");

		int processed = worker.runOnce();

		assertThat(processed).isEqualTo(1);
		EmailMessage message = messages.findById(id).orElseThrow();
		assertThat(message.getStatus()).isEqualTo(EmailStatus.SENT);
		assertThat(message.getProviderMessageId()).isEqualTo("re_123");
		assertThat(message.getAttemptCount()).isEqualTo(1);
		assertThat(message.getSentAt()).isNotNull();
		assertThat(message.getLastError()).isNull();

		RESEND.verify(postRequestedFor(urlEqualTo("/emails"))
				.withHeader("Authorization", equalTo("Bearer re_test_fake"))
				.withHeader("Idempotency-Key", equalTo(id.toString()))
				.withRequestBody(matchingJsonPath("$.from", equalTo("Acme <mail@acme.example>")))
				.withRequestBody(matchingJsonPath("$.to[0]", equalTo("player@example.com")))
				.withRequestBody(matchingJsonPath("$.subject", equalTo("Hello"))));
	}

	@Test
	void burstIsRetriedAndSucceeds() {
		RESEND.stubFor(post(urlEqualTo("/emails")).inScenario("burst")
				.whenScenarioStateIs(Scenario.STARTED)
				.willReturn(aResponse().withStatus(429)
						.withHeader("Retry-After", "0")
						.withBody("{\"name\":\"rate_limit_exceeded\"}"))
				.willSetStateTo("recovered"));
		RESEND.stubFor(post(urlEqualTo("/emails")).inScenario("burst")
				.whenScenarioStateIs("recovered")
				.willReturn(okJson("{\"id\":\"re_after_burst\"}")));

		UUID id = submit(newTenant(), "player@example.com");
		worker.runOnce();

		EmailMessage afterBurst = messages.findById(id).orElseThrow();
		assertThat(afterBurst.getStatus()).isEqualTo(EmailStatus.PENDING);
		assertThat(afterBurst.getAttemptCount()).isEqualTo(1);
		assertThat(afterBurst.getLastError()).contains("429");

		forceDue(id);
		worker.runOnce();

		EmailMessage sent = messages.findById(id).orElseThrow();
		assertThat(sent.getStatus()).isEqualTo(EmailStatus.SENT);
		assertThat(sent.getAttemptCount()).isEqualTo(2);
		assertThat(sent.getProviderMessageId()).isEqualTo("re_after_burst");
	}

	@Test
	void providerDailyQuotaParksTheMessageForHours() {
		RESEND.stubFor(post(urlEqualTo("/emails"))
				.willReturn(aResponse().withStatus(429).withBody("{\"name\":\"daily_quota_exceeded\"}")));
		UUID id = submit(newTenant(), "player@example.com");

		worker.runOnce();

		EmailMessage message = messages.findById(id).orElseThrow();
		assertThat(message.getStatus()).isEqualTo(EmailStatus.PENDING);
		assertThat(message.getNextAttemptAt()).isAfter(Instant.now().plus(Duration.ofHours(3)));
	}

	@Test
	void providerRejectionIsTerminalImmediately() {
		RESEND.stubFor(post(urlEqualTo("/emails"))
				.willReturn(aResponse().withStatus(422).withBody("{\"name\":\"validation_error\"}")));
		UUID id = submit(newTenant(), "player@example.com");

		worker.runOnce();

		EmailMessage message = messages.findById(id).orElseThrow();
		assertThat(message.getStatus()).isEqualTo(EmailStatus.FAILED);
		assertThat(message.getAttemptCount()).isEqualTo(1);
		assertThat(message.getLastError()).contains("http 422");
	}

	@Test
	void outagesBackOffAndEventuallyDeadLetter() {
		RESEND.stubFor(post(urlEqualTo("/emails")).willReturn(aResponse().withStatus(503)));
		UUID id = submit(newTenant(), "player@example.com");

		worker.runOnce();
		EmailMessage first = messages.findById(id).orElseThrow();
		assertThat(first.getStatus()).isEqualTo(EmailStatus.PENDING);
		assertThat(first.getNextAttemptAt()).isAfter(Instant.now().plus(Duration.ofSeconds(25)));

		forceDue(id);
		worker.runOnce();
		assertThat(messages.findById(id).orElseThrow().getStatus()).isEqualTo(EmailStatus.PENDING);

		// max-attempts is 3 in this test context.
		forceDue(id);
		worker.runOnce();
		EmailMessage dead = messages.findById(id).orElseThrow();
		assertThat(dead.getStatus()).isEqualTo(EmailStatus.FAILED);
		assertThat(dead.getAttemptCount()).isEqualTo(3);
	}

	@Test
	void recoveryReleasesRowsAbandonedInSending() {
		UUID id = submit(newTenant(), "player@example.com");
		jdbc.update("update email_messages set status = 'SENDING', updated_at = now() - interval '20 minutes' "
				+ "where id = ?", id);

		int released = store.releaseStuckSending(Duration.ofMinutes(10));

		assertThat(released).isEqualTo(1);
		assertThat(messages.findById(id).orElseThrow().getStatus()).isEqualTo(EmailStatus.PENDING);
	}

	@Test
	void recoveryLeavesRecentSendingAlone() {
		UUID id = submit(newTenant(), "player@example.com");
		jdbc.update("update email_messages set status = 'SENDING' where id = ?", id);

		assertThat(store.releaseStuckSending(Duration.ofMinutes(10))).isZero();
		assertThat(messages.findById(id).orElseThrow().getStatus()).isEqualTo(EmailStatus.SENDING);
	}

	@Test
	void concurrentClaimersNeverShareARow() throws Exception {
		Tenant tenant = newTenant();
		for (int i = 0; i < 20; i++) {
			submit(tenant, "player" + i + "@example.com");
		}

		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<List<EmailMessage>> left = executor.submit(() -> store.claimDueBatch(10));
			Future<List<EmailMessage>> right = executor.submit(() -> store.claimDueBatch(10));

			Set<UUID> ids = new HashSet<>();
			left.get().forEach(message -> ids.add(message.getId()));
			right.get().forEach(message -> ids.add(message.getId()));

			// 20 claims total, no overlap — SKIP LOCKED at work.
			assertThat(left.get()).hasSize(10);
			assertThat(right.get()).hasSize(10);
			assertThat(ids).hasSize(20);
		}
		finally {
			executor.shutdownNow();
		}
	}

	// --- helpers --------------------------------------------------------

	private Tenant newTenant() {
		return admin.createTenant("outbox-" + SLUGS.incrementAndGet(), "Acme",
				"Acme <mail@acme.example>", 500, 0);
	}

	private UUID submit(Tenant tenant, String to) {
		return submissions
				.submit(tenant.getId(), new SendEmailRequest(to, "Hello", "<p>Hi</p>", "Hi", null, null, null, null))
				.message()
				.getId();
	}

	private void forceDue(UUID id) {
		jdbc.update("update email_messages set next_attempt_at = now() - interval '1 second' where id = ?", id);
	}
}
