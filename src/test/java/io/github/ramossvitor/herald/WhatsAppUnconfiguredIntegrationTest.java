package io.github.ramossvitor.herald;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import io.github.ramossvitor.herald.email.EmailSubmissionService;
import io.github.ramossvitor.herald.email.SendEmailRequest;
import io.github.ramossvitor.herald.outbox.Message;
import io.github.ramossvitor.herald.outbox.MessageRepository;
import io.github.ramossvitor.herald.outbox.MessageStatus;
import io.github.ramossvitor.herald.outbox.OutboxWorker;
import io.github.ramossvitor.herald.tenant.Tenant;
import io.github.ramossvitor.herald.tenant.admin.TenantAdminService;

/**
 * What an operator gets with no encryption key set.
 *
 * The rule the whole design leans on: a channel without credentials pauses, it
 * does not fail. Messages queue, attempts are not spent, and the other channels
 * carry on — the same behaviour email has always had when RESEND_API_KEY is
 * missing, now shown to hold for a second channel and for a different reason.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
		"herald.admin-api-key=test-admin-master-key",
		// The point of this context: no key, so no credential can be stored or read.
		"herald.secret-key=",
		"herald.resend.api-key=re_test_fake",
		"herald.outbox.poll-interval=1h",
		"herald.outbox.send-interval=0ms",
		"herald.whatsapp.template-sync-interval=1h",
})
class WhatsAppUnconfiguredIntegrationTest {

	private static final WireMockServer RESEND = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
	private static final AtomicInteger SLUGS = new AtomicInteger();
	private static final String ADMIN = "Bearer test-admin-master-key";

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
	private MockMvc mvc;

	@Autowired
	private TenantAdminService admin;

	@Autowired
	private EmailSubmissionService submissions;

	@Autowired
	private OutboxWorker worker;

	@Autowired
	private MessageRepository messages;

	@Autowired
	private JdbcTemplate jdbc;

	private final ObjectMapper json = new ObjectMapper();

	@BeforeEach
	void reset() {
		RESEND.resetAll();
		jdbc.update("delete from messages");
	}

	@Test
	void queuedWhatsAppMessagesWaitWithoutSpendingAnAttempt() {
		RESEND.stubFor(WireMock.post(urlEqualTo("/emails")).willReturn(okJson("{\"id\":\"re_ok\"}")));
		Tenant tenant = admin.createTenant("unconf-" + SLUGS.incrementAndGet(), "Acme",
				"Acme <mail@acme.example>", 500, 0);

		UUID email = submissions
				.submit(tenant.getId(), new SendEmailRequest("player@example.com", "Hello", "<p>Hi</p>", "Hi",
						null, null, null, null))
				.message().getId();
		UUID chat = insertWhatsAppMessage(tenant);

		// Only the email is processed; the WhatsApp pass returns without a claim.
		assertThat(worker.runOnce()).isEqualTo(1);

		assertThat(messages.findById(email).orElseThrow().getStatus()).isEqualTo(MessageStatus.SENT);
		Message queued = messages.findById(chat).orElseThrow();
		assertThat(queued.getStatus()).isEqualTo(MessageStatus.PENDING);
		// Not a failed attempt: nothing was tried, so nothing was spent. Setting
		// the key later drains this backlog instead of finding it dead.
		assertThat(queued.getAttemptCount()).isZero();
		assertThat(queued.getLastError()).isNull();
	}

	@Test
	void registeringCredentialsIsRefusedRatherThanStoringThemInTheClear() throws Exception {
		MvcResult created = mvc.perform(post("/admin/v1/tenants")
				.header("Authorization", ADMIN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"slug":"noenc-%d","name":"Acme","email":{"fromAddress":"Acme <mail@acme.example>",
						 "dailyLimit":90,"recipientCooldownSeconds":0}}
						""".formatted(SLUGS.incrementAndGet())))
				.andExpect(status().isCreated())
				.andReturn();
		UUID tenantId = UUID.fromString(
				json.readTree(created.getResponse().getContentAsString()).get("id").asText());

		mvc.perform(post("/admin/v1/tenants/" + tenantId + "/whatsapp")
				.header("Authorization", ADMIN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"phoneNumberId":"15550001111","wabaId":"999","accessToken":"EAAtoken",
						 "appSecret":"secret","dailyLimit":90,"recipientCooldownSeconds":0}
						"""))
				.andExpect(status().isConflict());

		// Nothing was written, so nothing was written unprotected.
		assertThat(jdbc.queryForObject("select count(*) from tenant_whatsapp_settings where tenant_id = ?",
				Integer.class, tenantId)).isZero();
	}

	@Test
	void theSubscriptionHandshakeIsRefusedWhenNoTokenIsConfigured() throws Exception {
		// This context sets no verify token. Fail closed: an equality check
		// against an empty default would accept an empty token and let any
		// stranger point their Meta app at this instance and have it agree.
		mvc.perform(get("/webhooks/whatsapp")
				.param("hub.mode", "subscribe")
				.param("hub.verify_token", "")
				.param("hub.challenge", "challenge-value"))
				.andExpect(status().isForbidden())
				.andExpect(content().string(""));

		mvc.perform(get("/webhooks/whatsapp")
				.param("hub.mode", "subscribe")
				.param("hub.verify_token", "anything-at-all")
				.param("hub.challenge", "challenge-value"))
				.andExpect(status().isForbidden());
	}

	private UUID insertWhatsAppMessage(Tenant tenant) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				insert into messages (id, tenant_id, channel, recipient, recipient_canonical, sender, payload)
				values (?, ?, 'WHATSAPP', '+5511999990000', '5511999990000', '15550001111',
				        '{"template":"order_update","language":"pt_BR","params":[]}'::jsonb)
				""", id, tenant.getId());
		return id;
	}
}
