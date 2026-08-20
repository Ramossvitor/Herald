package io.github.ramossvitor.herald;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import io.github.ramossvitor.herald.email.EmailSubmissionService;
import io.github.ramossvitor.herald.email.SendEmailRequest;
import io.github.ramossvitor.herald.outbox.MessageRepository;
import io.github.ramossvitor.herald.outbox.MessageStatus;
import io.github.ramossvitor.herald.outbox.OutboxWorker;
import io.github.ramossvitor.herald.quota.QuotaExceededException;
import io.github.ramossvitor.herald.sender.Channel;
import io.github.ramossvitor.herald.tenant.Tenant;
import io.github.ramossvitor.herald.tenant.admin.TenantAdminService;
import io.github.ramossvitor.herald.whatsapp.WhatsAppAdminService;

/**
 * The point of the polymorphic outbox: two channels sharing the claim, retry
 * and recovery machinery without sharing budgets or failure.
 *
 * Both providers here are the real ones, each pointed at its own WireMock. An
 * earlier version stubbed the second channel because it did not exist yet;
 * keeping the stub now would only prove that a fake behaves.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
		"herald.resend.api-key=re_test_fake",
		"herald.secret-key=aGVyYWxkLXRlc3Qta2V5LTAxMjM0NTY3ODlhYmNkZWY=",
		"herald.outbox.poll-interval=1h",
		"herald.outbox.send-interval=0ms",
		"herald.whatsapp.send-interval=0ms",
		"herald.whatsapp.template-sync-interval=1h",
})
class ChannelIsolationIntegrationTest {

	private static final WireMockServer RESEND = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
	private static final WireMockServer META = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
	private static final AtomicInteger SLUGS = new AtomicInteger();

	@DynamicPropertySource
	static void providerEndpoints(DynamicPropertyRegistry registry) {
		RESEND.start();
		META.start();
		registry.add("herald.resend.base-url", RESEND::baseUrl);
		registry.add("herald.whatsapp.base-url", META::baseUrl);
	}

	@AfterAll
	static void stopWireMock() {
		RESEND.stop();
		META.stop();
	}

	@Autowired
	private TenantAdminService admin;

	@Autowired
	private EmailSubmissionService submissions;

	@Autowired
	private WhatsAppAdminService whatsappAdmin;

	@Autowired
	private OutboxWorker worker;

	@Autowired
	private MessageRepository messages;

	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	void reset() {
		RESEND.resetAll();
		META.resetAll();
		jdbc.update("delete from messages");
	}

	@Test
	void oneChannelFailingDoesNotHoldUpTheOther() {
		RESEND.stubFor(post(urlEqualTo("/emails")).willReturn(okJson("{\"id\":\"re_ok\"}")));
		META.stubFor(post(urlPathMatching(".*/messages")).willReturn(aResponse().withStatus(503)));

		Tenant tenant = newTenantWithWhatsApp(500);
		UUID email = submitEmail(tenant, "player@example.com");
		UUID chat = insertWhatsAppMessage(tenant, "+5511999990000", null);

		// A single pass: each channel is claimed and drained on its own.
		assertThat(worker.runOnce()).isEqualTo(2);

		assertThat(messages.findById(email).orElseThrow().getStatus()).isEqualTo(MessageStatus.SENT);
		assertThat(messages.findById(chat).orElseThrow().getStatus()).isEqualTo(MessageStatus.PENDING);
	}

	@Test
	void aChannelCanBeDrainedOnItsOwn() {
		RESEND.stubFor(post(urlEqualTo("/emails")).willReturn(okJson("{\"id\":\"re_ok\"}")));
		stubWhatsAppSend("wamid.one");

		Tenant tenant = newTenantWithWhatsApp(500);
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

	/** A tenant whose WhatsApp credentials are registered and proven, so the
	 * dispatch path has something to send under. */
	private Tenant newTenantWithWhatsApp(int dailyLimit) {
		Tenant tenant = newTenant(dailyLimit);
		META.stubFor(get(urlMatching(".*/15550001111\\?.*"))
				.willReturn(okJson("{\"id\":\"15550001111\",\"display_phone_number\":\"+1 555 000 1111\"}")));
		META.stubFor(get(urlPathMatching(".*/message_templates")).willReturn(okJson("{\"data\":[]}")));
		whatsappAdmin.register(tenant.getId(), new WhatsAppAdminService.Credentials(
				"15550001111", "waba-" + tenant.getId(), "EAAtoken", "appsecret", dailyLimit, 0));
		return tenant;
	}

	private void stubWhatsAppSend(String wamid) {
		META.stubFor(post(urlPathMatching(".*/messages")).willReturn(okJson(
				"{\"messaging_product\":\"whatsapp\",\"messages\":[{\"id\":\"" + wamid + "\"}]}")));
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

	/** Seeded directly: these tests are about the outbox, not about how a
	 * WhatsApp row comes to exist. */
	private UUID insertWhatsAppMessage(Tenant tenant, String recipient, String idempotencyKey, String... limitKeys) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				insert into messages (id, tenant_id, channel, idempotency_key, recipient, recipient_canonical,
				                      sender, payload, limit_keys)
				values (?, ?, 'WHATSAPP', ?, ?, ?, '15550001111',
				        '{"template":"order_update","language":"pt_BR","params":[]}'::jsonb,
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
