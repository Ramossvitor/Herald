package io.github.ramossvitor.herald;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import io.github.ramossvitor.herald.outbox.OutboxWorker;

/**
 * The delivery-feedback loop end to end: send, have the provider report what
 * happened, and watch that decide whether the next send is even accepted.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
		"herald.admin-api-key=test-admin-master-key",
		"herald.resend.api-key=re_test_fake",
		// base64("testsecretforwebhookverification")
		"herald.resend.webhook-secret=whsec_dGVzdHNlY3JldGZvcndlYmhvb2t2ZXJpZmljYXRpb24=",
		"herald.outbox.poll-interval=1h",
		"herald.outbox.send-interval=0ms",
})
class WebhookIntegrationTest {

	private static final String ADMIN = "Bearer test-admin-master-key";
	private static final String SECRET = "whsec_dGVzdHNlY3JldGZvcndlYmhvb2t2ZXJpZmljYXRpb24=";
	private static final String WEBHOOK = "/v1/webhooks/resend";
	private static final AtomicInteger SLUGS = new AtomicInteger();

	private static final WireMockServer RESEND = new WireMockServer(
			WireMockConfiguration.wireMockConfig().dynamicPort());

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
	private OutboxWorker worker;

	@Autowired
	private JdbcTemplate jdbc;

	private final ObjectMapper json = new ObjectMapper();

	@BeforeEach
	void reset() {
		RESEND.resetAll();
		jdbc.update("delete from webhook_events");
		jdbc.update("delete from suppressions");
		jdbc.update("delete from messages");
	}

	// --- the happy path -------------------------------------------------

	@Test
	void aDeliveryReportBecomesTheMessagesDeliveryState() throws Exception {
		Provisioned tenant = provisionTenant();
		Sent sent = sendAndDispatch(tenant, "player@example.com");

		assertThat(emailStatus(tenant, sent.id).get("status").asText()).isEqualTo("SENT");
		assertThat(emailStatus(tenant, sent.id).get("deliveryState").isNull()).isTrue();

		mvc.perform(signed(delivered(sent.providerId))).andExpect(status().isOk());

		JsonNode after = emailStatus(tenant, sent.id);
		assertThat(after.get("status").asText()).isEqualTo("SENT");
		assertThat(after.get("deliveryState").asText()).isEqualTo("DELIVERED");
		assertThat(after.get("deliveryUpdatedAt").isNull()).isFalse();
	}

	// --- suppression ----------------------------------------------------

	@Test
	void aPermanentBounceSuppressesTheAddressAndTheNextSendIs422() throws Exception {
		Provisioned tenant = provisionTenant();
		Sent sent = sendAndDispatch(tenant, "ghost@example.com");

		mvc.perform(signed(bounce(sent.providerId, "Permanent"))).andExpect(status().isOk());

		assertThat(emailStatus(tenant, sent.id).get("deliveryState").asText()).isEqualTo("BOUNCED");

		mvc.perform(sendEmail(tenant, "ghost@example.com"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.type").value("/errors/recipient-suppressed"))
				.andExpect(jsonPath("$.reason").value("bounced"))
				.andExpect(jsonPath("$.recipient").value("ghost@example.com"));

		mvc.perform(get("/v1/suppressions").header("Authorization", tenant.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].recipient").value("ghost@example.com"))
				.andExpect(jsonPath("$[0].reason").value("bounced"))
				.andExpect(jsonPath("$[0].sourceMessageId").value(sent.id));
	}

	@Test
	void aTransientBounceIsRecordedButKeepsTheAddressMailable() throws Exception {
		Provisioned tenant = provisionTenant();
		Sent sent = sendAndDispatch(tenant, "busy@example.com");

		mvc.perform(signed(bounce(sent.providerId, "Transient"))).andExpect(status().isOk());

		assertThat(emailStatus(tenant, sent.id).get("deliveryState").asText()).isEqualTo("BOUNCED");
		// A full mailbox is not a dead one.
		mvc.perform(sendEmail(tenant, "busy@example.com")).andExpect(status().isAccepted());
	}

	@Test
	void aComplaintSuppressesToo() throws Exception {
		Provisioned tenant = provisionTenant();
		Sent sent = sendAndDispatch(tenant, "annoyed@example.com");

		mvc.perform(signed(event("email.complained", sent.providerId))).andExpect(status().isOk());

		mvc.perform(sendEmail(tenant, "annoyed@example.com"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.reason").value("complained"));
	}

	@Test
	void suppressionFollowsTheMailboxNotTheSpelling() throws Exception {
		Provisioned tenant = provisionTenant();
		Sent sent = sendAndDispatch(tenant, "a.b+promo@gmail.com");

		mvc.perform(signed(bounce(sent.providerId, "Permanent"))).andExpect(status().isOk());

		// Same Gmail mailbox, different spelling — and so still refused.
		mvc.perform(sendEmail(tenant, "ab@gmail.com")).andExpect(status().isUnprocessableEntity());
	}

	@Test
	void aSuppressionIsScopedToItsTenant() throws Exception {
		Provisioned first = provisionTenant();
		Provisioned second = provisionTenant();
		Sent sent = sendAndDispatch(first, "shared@example.com");

		mvc.perform(signed(bounce(sent.providerId, "Permanent"))).andExpect(status().isOk());

		mvc.perform(sendEmail(first, "shared@example.com")).andExpect(status().isUnprocessableEntity());
		// Different sending domain, different reputation, different decision.
		mvc.perform(sendEmail(second, "shared@example.com")).andExpect(status().isAccepted());
		mvc.perform(get("/v1/suppressions").header("Authorization", second.bearer()))
				.andExpect(jsonPath("$").isEmpty());
	}

	@Test
	void liftingASuppressionMakesTheAddressMailableAgain() throws Exception {
		Provisioned tenant = provisionTenant();
		Sent sent = sendAndDispatch(tenant, "fixed@example.com");
		mvc.perform(signed(bounce(sent.providerId, "Permanent"))).andExpect(status().isOk());
		mvc.perform(sendEmail(tenant, "fixed@example.com")).andExpect(status().isUnprocessableEntity());

		mvc.perform(delete("/v1/suppressions/fixed@example.com").header("Authorization", tenant.bearer()))
				.andExpect(status().isNoContent());

		mvc.perform(sendEmail(tenant, "fixed@example.com")).andExpect(status().isAccepted());
	}

	@Test
	void liftingWorksThroughAnySpellingOfTheSameMailbox() throws Exception {
		Provisioned tenant = provisionTenant();
		Sent sent = sendAndDispatch(tenant, "a.b+tag@gmail.com");
		mvc.perform(signed(bounce(sent.providerId, "Permanent"))).andExpect(status().isOk());

		mvc.perform(delete("/v1/suppressions/a.b@gmail.com").header("Authorization", tenant.bearer()))
				.andExpect(status().isNoContent());

		mvc.perform(sendEmail(tenant, "ab@gmail.com")).andExpect(status().isAccepted());
	}

	@Test
	void liftingSomethingThatIsNotSuppressedIsANotFound() throws Exception {
		Provisioned tenant = provisionTenant();

		mvc.perform(delete("/v1/suppressions/nobody@example.com").header("Authorization", tenant.bearer()))
				.andExpect(status().isNotFound());
	}

	@Test
	void suppressionsAreNotVisibleToOtherTenants() throws Exception {
		Provisioned tenant = provisionTenant();

		mvc.perform(get("/v1/suppressions")).andExpect(status().isUnauthorized());
	}

	// --- ordering and replay --------------------------------------------

	@Test
	void aLateDeliveredDoesNotUndoABounce() throws Exception {
		Provisioned tenant = provisionTenant();
		Sent sent = sendAndDispatch(tenant, "ghost@example.com");

		mvc.perform(signed(bounce(sent.providerId, "Permanent"))).andExpect(status().isOk());
		mvc.perform(signed(delivered(sent.providerId))).andExpect(status().isOk());

		// Otherwise the message would claim success while the suppression list
		// says the address is dead.
		assertThat(emailStatus(tenant, sent.id).get("deliveryState").asText()).isEqualTo("BOUNCED");
		mvc.perform(sendEmail(tenant, "ghost@example.com")).andExpect(status().isUnprocessableEntity());
	}

	@Test
	void redeliveringAnEventChangesNothing() throws Exception {
		Provisioned tenant = provisionTenant();
		Sent sent = sendAndDispatch(tenant, "ghost@example.com");
		String body = bounce(sent.providerId, "Permanent");
		String svixId = "msg_" + UUID.randomUUID();

		mvc.perform(signed(svixId, body)).andExpect(status().isOk());
		mvc.perform(signed(svixId, body)).andExpect(status().isOk());

		assertThat(jdbc.queryForObject("select count(*) from suppressions", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from webhook_events", Integer.class)).isEqualTo(1);
	}

	@Test
	void aDelayIsProvisionalAndLaterNewsWins() throws Exception {
		Provisioned tenant = provisionTenant();
		Sent sent = sendAndDispatch(tenant, "slow@example.com");

		mvc.perform(signed(event("email.delivery_delayed", sent.providerId))).andExpect(status().isOk());
		assertThat(emailStatus(tenant, sent.id).get("deliveryState").asText()).isEqualTo("DELAYED");

		mvc.perform(signed(delivered(sent.providerId))).andExpect(status().isOk());
		assertThat(emailStatus(tenant, sent.id).get("deliveryState").asText()).isEqualTo("DELIVERED");
	}

	@Test
	void aDelayThatArrivesAfterTheDeliveryDoesNotUndoIt() throws Exception {
		Provisioned tenant = provisionTenant();
		Sent sent = sendAndDispatch(tenant, "slow@example.com");

		// The same two events as above, overtaking each other on the way here.
		// The delay is older news whichever order it lands in, and a message
		// that arrived must not go back to reading as still in flight.
		mvc.perform(signed(delivered(sent.providerId))).andExpect(status().isOk());
		mvc.perform(signed(event("email.delivery_delayed", sent.providerId))).andExpect(status().isOk());

		assertThat(emailStatus(tenant, sent.id).get("deliveryState").asText()).isEqualTo("DELIVERED");
	}

	@Test
	void aSecondBounceForTheSameAddressKeepsBothItsMessagesAndOneSuppression() throws Exception {
		Provisioned tenant = provisionTenant();
		// Both dispatched before either bounces — afterwards the address is
		// suppressed and a second send would never be accepted.
		Sent first = sendAndDispatch(tenant, "ghost@example.com");
		Sent second = sendAndDispatch(tenant, "ghost@example.com");

		mvc.perform(signed(bounce(first.providerId, "Permanent"))).andExpect(status().isOk());
		mvc.perform(signed(bounce(second.providerId, "Permanent"))).andExpect(status().isOk());

		// The second event finds the address already suppressed. That must cost
		// it nothing else: its own message still records what happened to it.
		assertThat(jdbc.queryForObject("select count(*) from suppressions", Integer.class)).isEqualTo(1);
		assertThat(emailStatus(tenant, first.id).get("deliveryState").asText()).isEqualTo("BOUNCED");
		assertThat(emailStatus(tenant, second.id).get("deliveryState").asText()).isEqualTo("BOUNCED");
		assertThat(jdbc.queryForObject("select count(*) from webhook_events", Integer.class)).isEqualTo(2);
	}

	// --- authentication -------------------------------------------------

	@Test
	void anUnsignedWebhookIsRefusedAndChangesNothing() throws Exception {
		Provisioned tenant = provisionTenant();
		Sent sent = sendAndDispatch(tenant, "ghost@example.com");

		mvc.perform(post(WEBHOOK)
				.contentType(MediaType.APPLICATION_JSON)
				.content(bounce(sent.providerId, "Permanent")))
				.andExpect(status().isUnauthorized());

		assertThat(emailStatus(tenant, sent.id).get("deliveryState").isNull()).isTrue();
		assertThat(jdbc.queryForObject("select count(*) from suppressions", Integer.class)).isZero();
	}

	@Test
	void aForgedSignatureIsRefused() throws Exception {
		Provisioned tenant = provisionTenant();
		Sent sent = sendAndDispatch(tenant, "ghost@example.com");
		String body = bounce(sent.providerId, "Permanent");

		mvc.perform(post(WEBHOOK)
				.header("svix-id", "msg_forged")
				.header("svix-timestamp", String.valueOf(Instant.now().getEpochSecond()))
				.header("svix-signature", "v1,YWJzb2x1dGVseW5vdHRoZXJpZ2h0c2lnbmF0dXJlaGVyZQ==")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isUnauthorized());

		assertThat(jdbc.queryForObject("select count(*) from suppressions", Integer.class)).isZero();
	}

	@Test
	void aReplayedRequestFallsOutsideTheToleranceWindow() throws Exception {
		Provisioned tenant = provisionTenant();
		Sent sent = sendAndDispatch(tenant, "ghost@example.com");
		String body = bounce(sent.providerId, "Permanent");
		// Correctly signed for an hour ago — a captured request replayed later.
		long stale = Instant.now().minus(Duration.ofHours(1)).getEpochSecond();

		mvc.perform(signedAt("msg_replayed", body, stale)).andExpect(status().isUnauthorized());

		assertThat(jdbc.queryForObject("select count(*) from suppressions", Integer.class)).isZero();
	}

	@Test
	void anOversizedBodyIsRefusedWithoutBeingRead() throws Exception {
		// The endpoint answers without a key, so the memory a stranger can make
		// it spend has to be bounded before the signature is even looked at.
		String huge = "{\"type\":\"email.bounced\",\"pad\":\"" + "x".repeat(300 * 1024) + "\"}";

		mvc.perform(post(WEBHOOK)
				.header("svix-id", "msg_" + UUID.randomUUID())
				.header("svix-timestamp", String.valueOf(Instant.now().getEpochSecond()))
				.header("svix-signature", "v1,irrelevant")
				.contentType(MediaType.APPLICATION_JSON)
				.content(huge))
				.andExpect(status().isPayloadTooLarge());

		assertThat(jdbc.queryForObject("select count(*) from webhook_events", Integer.class)).isZero();
	}

	@Test
	void aTamperedBodyBreaksTheSignature() throws Exception {
		Provisioned tenant = provisionTenant();
		Sent sent = sendAndDispatch(tenant, "ghost@example.com");
		String signedBody = delivered(sent.providerId);
		String svixId = "msg_" + UUID.randomUUID();
		long timestamp = Instant.now().getEpochSecond();
		String signature = sign(svixId, timestamp, signedBody);

		// The signature is real; the body it travels with is not the one signed.
		mvc.perform(post(WEBHOOK)
				.header("svix-id", svixId)
				.header("svix-timestamp", String.valueOf(timestamp))
				.header("svix-signature", signature)
				.contentType(MediaType.APPLICATION_JSON)
				.content(bounce(sent.providerId, "Permanent")))
				.andExpect(status().isUnauthorized());

		assertThat(jdbc.queryForObject("select count(*) from suppressions", Integer.class)).isZero();
	}

	// --- events with nothing to act on ----------------------------------

	@Test
	void anEventForAnUnknownMessageIsAcceptedAndIgnored() throws Exception {
		// Mail sent through the same provider account but not through Herald.
		mvc.perform(signed(delivered("re_never_seen_here"))).andExpect(status().isOk());

		assertThat(jdbc.queryForObject("select count(*) from suppressions", Integer.class)).isZero();
	}

	@Test
	void untrackedEventTypesAreAccepted() throws Exception {
		Provisioned tenant = provisionTenant();
		Sent sent = sendAndDispatch(tenant, "player@example.com");

		mvc.perform(signed(event("email.opened", sent.providerId))).andExpect(status().isOk());

		// Recorded as seen, but it says nothing about delivery.
		assertThat(emailStatus(tenant, sent.id).get("deliveryState").isNull()).isTrue();
	}

	@Test
	void anAuthenticButUnreadableBodyIsNotRetriedForever() throws Exception {
		// A 4xx here would make the provider redeliver an identical payload
		// until it gave up.
		mvc.perform(signed("{\"unexpected\":\"shape\"}")).andExpect(status().isOk());
	}

	// --- helpers --------------------------------------------------------

	private record Provisioned(UUID tenantId, String apiKey) {

		String bearer() {
			return "Bearer " + apiKey;
		}
	}

	private record Sent(String id, String providerId) {
	}

	private Provisioned provisionTenant() throws Exception {
		String slug = "hook-" + SLUGS.incrementAndGet();
		MvcResult created = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.post("/admin/v1/tenants")
				.header("Authorization", ADMIN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"slug":"%s","name":"Acme","email":{"fromAddress":"Acme <mail@acme.example>",
						"dailyLimit":500,"recipientCooldownSeconds":0}}
						""".formatted(slug)))
				.andExpect(status().isCreated())
				.andReturn();
		UUID tenantId = UUID.fromString(json.readTree(created.getResponse().getContentAsString()).get("id").asText());

		MvcResult issued = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.post("/admin/v1/tenants/" + tenantId + "/api-keys")
				.header("Authorization", ADMIN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"label\":\"test\"}"))
				.andExpect(status().isCreated())
				.andReturn();
		return new Provisioned(tenantId,
				json.readTree(issued.getResponse().getContentAsString()).get("apiKey").asText());
	}

	/** Submits and runs the worker, so the row carries a provider id to match on. */
	private Sent sendAndDispatch(Provisioned tenant, String to) throws Exception {
		String providerId = "re_" + UUID.randomUUID();
		RESEND.stubFor(WireMock.post(urlEqualTo("/emails")).willReturn(okJson("{\"id\":\"" + providerId + "\"}")));

		MvcResult accepted = mvc.perform(sendEmail(tenant, to)).andExpect(status().isAccepted()).andReturn();
		String id = json.readTree(accepted.getResponse().getContentAsString()).get("id").asText();
		worker.runOnce();
		return new Sent(id, providerId);
	}

	private MockHttpServletRequestBuilder sendEmail(Provisioned tenant, String to) {
		return post("/v1/emails")
				.header("Authorization", tenant.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"to":"%s","subject":"Hello","html":"<p>Hi</p>","text":"Hi"}
						""".formatted(to));
	}

	private JsonNode emailStatus(Provisioned tenant, String id) throws Exception {
		MvcResult result = mvc.perform(get("/v1/emails/" + id).header("Authorization", tenant.bearer()))
				.andExpect(status().isOk())
				.andReturn();
		return json.readTree(result.getResponse().getContentAsString());
	}

	// --- payloads and signing -------------------------------------------

	private static String delivered(String providerId) {
		return event("email.delivered", providerId);
	}

	private static String event(String type, String providerId) {
		return """
				{"type":"%s","created_at":"2026-08-25T10:00:00.000Z",\
				"data":{"email_id":"%s","to":["player@example.com"]}}"""
				.formatted(type, providerId);
	}

	private static String bounce(String providerId, String bounceType) {
		return """
				{"type":"email.bounced","created_at":"2026-08-25T10:00:00.000Z","data":{\
				"email_id":"%s","to":["player@example.com"],\
				"bounce":{"message":"Mailbox unavailable.","subType":"General","type":"%s"}}}"""
				.formatted(providerId, bounceType);
	}

	private MockHttpServletRequestBuilder signed(String body) {
		return signed("msg_" + UUID.randomUUID(), body);
	}

	private MockHttpServletRequestBuilder signed(String svixId, String body) {
		return signedAt(svixId, body, Instant.now().getEpochSecond());
	}

	private MockHttpServletRequestBuilder signedAt(String svixId, String body, long timestamp) {
		return post(WEBHOOK)
				.header("svix-id", svixId)
				.header("svix-timestamp", String.valueOf(timestamp))
				.header("svix-signature", sign(svixId, timestamp, body))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body);
	}

	/**
	 * Signs the way the provider does. Written out here rather than reused from
	 * the verifier so a change to one has to be made deliberately in the other;
	 * the algorithm itself is pinned to a published vector in
	 * {@code SvixSignatureVerifierTest}.
	 */
	private static String sign(String svixId, long timestamp, String body) {
		try {
			byte[] key = Base64.getDecoder().decode(SECRET.substring("whsec_".length()));
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
			byte[] signature = mac.doFinal(
					(svixId + "." + timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
			return "v1," + Base64.getEncoder().encodeToString(signature);
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}
}
