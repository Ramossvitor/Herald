package io.github.ramossvitor.herald;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import io.github.ramossvitor.herald.email.EmailSubmissionService;
import io.github.ramossvitor.herald.email.SendEmailRequest;
import io.github.ramossvitor.herald.outbox.ChannelProvider;
import io.github.ramossvitor.herald.outbox.Classification;
import io.github.ramossvitor.herald.outbox.Message;
import io.github.ramossvitor.herald.outbox.MessageRepository;
import io.github.ramossvitor.herald.outbox.MessageStatus;
import io.github.ramossvitor.herald.outbox.OutboxWorker;
import io.github.ramossvitor.herald.quota.QuotaExceededException;
import io.github.ramossvitor.herald.sender.Channel;
import io.github.ramossvitor.herald.tenant.Tenant;
import io.github.ramossvitor.herald.tenant.admin.TenantAdminService;

/**
 * The point of the polymorphic outbox: two channels sharing the claim, retry
 * and recovery machinery without sharing budgets or failure. A second channel
 * is stubbed here because the real one (WhatsApp) is bring-your-own and cannot
 * be exercised without a tenant's own credentials.
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, ChannelIsolationIntegrationTest.StubWhatsAppConfig.class })
@TestPropertySource(properties = {
		"herald.resend.api-key=re_test_fake",
		"herald.outbox.poll-interval=1h",
		"herald.outbox.send-interval=0ms",
})
class ChannelIsolationIntegrationTest {

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

	/** A stand-in for the WhatsApp provider, steerable per test. */
	static class StubWhatsAppProvider implements ChannelProvider {

		boolean configured = true;
		Classification classification = Classification.SUCCESS;
		int sendCalls = 0;

		@Override
		public Channel channel() {
			return Channel.WHATSAPP;
		}

		@Override
		public boolean configured() {
			return configured;
		}

		@Override
		public Attempt send(Message message) {
			sendCalls++;
			return classification == Classification.SUCCESS
					? Attempt.success("wamid.stub")
					: Attempt.failed(classification, "stubbed " + classification, null);
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class StubWhatsAppConfig {

		@Bean
		StubWhatsAppProvider stubWhatsAppProvider() {
			return new StubWhatsAppProvider();
		}
	}

	@Autowired
	private TenantAdminService admin;

	@Autowired
	private EmailSubmissionService submissions;

	@Autowired
	private OutboxWorker worker;

	@Autowired
	private MessageRepository messages;

	@Autowired
	private StubWhatsAppProvider whatsapp;

	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	void reset() {
		RESEND.resetAll();
		jdbc.update("delete from messages");
		whatsapp.configured = true;
		whatsapp.classification = Classification.SUCCESS;
		whatsapp.sendCalls = 0;
	}

	@Test
	void oneChannelFailingDoesNotHoldUpTheOther() {
		RESEND.stubFor(post(urlEqualTo("/emails")).willReturn(okJson("{\"id\":\"re_ok\"}")));
		whatsapp.classification = Classification.UNAVAILABLE;

		Tenant tenant = newTenant(500);
		UUID email = submitEmail(tenant, "player@example.com");
		UUID chat = insertWhatsAppMessage(tenant, "+5511999990000", null);

		// A single pass: each channel is claimed and drained on its own.
		assertThat(worker.runOnce()).isEqualTo(2);

		assertThat(messages.findById(email).orElseThrow().getStatus()).isEqualTo(MessageStatus.SENT);
		assertThat(messages.findById(chat).orElseThrow().getStatus()).isEqualTo(MessageStatus.PENDING);
	}

	@Test
	void anUnconfiguredChannelQueuesWithoutTouchingTheOthers() {
		RESEND.stubFor(post(urlEqualTo("/emails")).willReturn(okJson("{\"id\":\"re_ok\"}")));
		whatsapp.configured = false;

		Tenant tenant = newTenant(500);
		UUID email = submitEmail(tenant, "player@example.com");
		UUID chat = insertWhatsAppMessage(tenant, "+5511999990000", null);

		assertThat(worker.runOnce()).isEqualTo(1);

		assertThat(messages.findById(email).orElseThrow().getStatus()).isEqualTo(MessageStatus.SENT);
		// Queued, never attempted: no provider call, no attempt burned.
		Message queued = messages.findById(chat).orElseThrow();
		assertThat(queued.getStatus()).isEqualTo(MessageStatus.PENDING);
		assertThat(queued.getAttemptCount()).isZero();
		assertThat(whatsapp.sendCalls).isZero();
	}

	@Test
	void aChannelCanBeDrainedOnItsOwn() {
		RESEND.stubFor(post(urlEqualTo("/emails")).willReturn(okJson("{\"id\":\"re_ok\"}")));

		Tenant tenant = newTenant(500);
		UUID email = submitEmail(tenant, "player@example.com");
		UUID chat = insertWhatsAppMessage(tenant, "+5511999990000", null);

		assertThat(worker.runOnce(Channel.WHATSAPP)).isEqualTo(1);

		assertThat(messages.findById(chat).orElseThrow().getStatus()).isEqualTo(MessageStatus.SENT);
		assertThat(messages.findById(email).orElseThrow().getStatus()).isEqualTo(MessageStatus.PENDING);
	}

	// --- quota is per channel -------------------------------------------

	@Test
	void oneChannelsDailyBudgetCannotBeSpentByAnother() {
		Tenant tenant = newTenant(2);
		// Exhaust what would be the shared allowance, on the other channel.
		insertWhatsAppMessage(tenant, "+5511999990001", null);
		insertWhatsAppMessage(tenant, "+5511999990002", null);
		insertWhatsAppMessage(tenant, "+5511999990003", null);

		// Email's own budget is untouched.
		submitEmail(tenant, "a@example.com");
		submitEmail(tenant, "b@example.com");

		// ...and still runs out on its own terms.
		assertThatThrownBy(() -> submitEmail(tenant, "c@example.com"))
				.isInstanceOf(QuotaExceededException.class);
	}

	@Test
	void anIdempotencyKeyMeansOneDeliveryPerChannelNotOneOverall() {
		Tenant tenant = newTenant(500);
		insertWhatsAppMessage(tenant, "+5511999990000", "order-42");

		// The same key on another channel is a delivery the caller wants, not a
		// replay: two notifications about one order, by two routes.
		UUID email = submitEmail(tenant, "player@example.com", "order-42");
		assertThat(messages.findById(email).orElseThrow().getChannel()).isEqualTo(Channel.EMAIL);

		// On its own channel it still collapses.
		assertThat(submissions.submit(tenant.getId(), new SendEmailRequest(
				"player@example.com", "Hello", "<p>Hi</p>", "Hi", null, null, "order-42", null)).deduplicated())
				.isTrue();
	}

	@Test
	void aLimitKeyCapIsSpentPerChannel() {
		Tenant tenant = newTenant(500);
		admin.replaceLimitPolicies(tenant.getId(), List.of(new TenantAdminService.PolicyInput("inviter", 1)));

		// The cap is one per key per day. Spend it on the other channel.
		insertWhatsAppMessage(tenant, "+5511999990000", null, "inviter:7");
		// Pinned, so that a seed that quietly stored no key at all could not
		// pass this test by looking like a budget that was never spent.
		assertThat(messages.countWithLimitKeySince(tenant.getId(), "WHATSAPP", "inviter:7", Instant.EPOCH))
				.isEqualTo(1);

		// Email's cap for the same key is its own. This is the one counter that
		// reaches the database as a native query with the channel as raw text,
		// so a scoping slip here would be silent everywhere else.
		submitEmail(tenant, "player@example.com", null, "inviter:7");

		// ...and still runs out on its own terms.
		assertThatThrownBy(() -> submitEmail(tenant, "other@example.com", null, "inviter:7"))
				.isInstanceOf(QuotaExceededException.class);
	}

	@Test
	void aRecipientCooldownIsCountedPerChannel() {
		Tenant tenant = newTenant(500, 3600);
		// Same person, both channels: reaching someone on WhatsApp must not
		// close the door on emailing them.
		insertWhatsAppMessage(tenant, "player@example.com", null);

		submitEmail(tenant, "player@example.com");

		// ...while a second email inside the window is still refused.
		assertThatThrownBy(() -> submitEmail(tenant, "player@example.com"))
				.isInstanceOf(QuotaExceededException.class);
	}

	// --- helpers --------------------------------------------------------

	private Tenant newTenant(int dailyLimit) {
		return newTenant(dailyLimit, 0);
	}

	private Tenant newTenant(int dailyLimit, int recipientCooldownSeconds) {
		return admin.createTenant("chan-" + SLUGS.incrementAndGet(), "Acme",
				"Acme <mail@acme.example>", dailyLimit, recipientCooldownSeconds);
	}

	private UUID submitEmail(Tenant tenant, String to) {
		return submitEmail(tenant, to, null);
	}

	private UUID submitEmail(Tenant tenant, String to, String idempotencyKey, String... limitKeys) {
		return submissions
				.submit(tenant.getId(), new SendEmailRequest(to, "Hello", "<p>Hi</p>", "Hi", null, null,
						idempotencyKey, limitKeys.length == 0 ? null : List.of(limitKeys)))
				.message()
				.getId();
	}

	/** No WhatsApp submission path exists yet; the row is what the worker sees. */
	private UUID insertWhatsAppMessage(Tenant tenant, String recipient, String idempotencyKey, String... limitKeys) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				insert into messages (id, tenant_id, channel, idempotency_key, recipient, recipient_canonical,
				                      sender, payload, limit_keys)
				values (?, ?, 'WHATSAPP', ?, ?, ?, '15550001111', '{"template":"order_update"}'::jsonb,
				        cast(? as text[]))
				""", id, tenant.getId(), idempotencyKey, recipient, recipient, arrayLiteral(limitKeys));
		return id;
	}

	/** Postgres array literal: {@code {}} when empty, quoted elements otherwise. */
	private static String arrayLiteral(String... values) {
		return values.length == 0
				? "{}"
				: Stream.of(values).collect(Collectors.joining("\",\"", "{\"", "\"}"));
	}
}
