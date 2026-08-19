package io.github.ramossvitor.herald;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The whole API surface against a real database: provisioning, key auth,
 * submission, idempotency and the contractual order of quota rejections.
 * Dispatch never runs here (no provider key), so accepted rows stay PENDING.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
		"herald.admin-api-key=test-admin-master-key",
		"herald.email.shared-root-domain=send.test.example",
		"herald.resend.api-key=",
		"herald.outbox.poll-interval=1h",
})
class ApiFlowIntegrationTest {

	private static final String ADMIN = "Bearer test-admin-master-key";
	private static final AtomicInteger SLUGS = new AtomicInteger();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	// Deliberately not the context's bean: Boot 4 wires Jackson 3 there, and
	// these tests only need a local JSON parser.
	private final ObjectMapper json = new ObjectMapper();

	// --- authentication -------------------------------------------------

	@Test
	void withoutAKeyEverythingButHealthIs401() throws Exception {
		mvc.perform(post("/v1/emails")).andExpect(status().isUnauthorized());
		mvc.perform(get("/admin/v1/tenants")).andExpect(status().isUnauthorized());
		mvc.perform(get("/actuator/health")).andExpect(status().isOk());
	}

	@Test
	void madeUpKeysAndRevokedKeysAre401() throws Exception {
		mvc.perform(get("/v1/emails/" + UUID.randomUUID())
				.header("Authorization", "Bearer hrl_live_made-up-key-that-matches-nothing"))
				.andExpect(status().isUnauthorized());

		Provisioned tenant = provisionTenant(90, 600);
		mvc.perform(delete("/admin/v1/api-keys/" + tenant.keyId).header("Authorization", ADMIN))
				.andExpect(status().isNoContent());
		mvc.perform(get("/v1/emails/" + UUID.randomUUID()).header("Authorization", tenant.bearer()))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void tenantKeysCannotReachTheAdminSurface() throws Exception {
		Provisioned tenant = provisionTenant(90, 600);
		mvc.perform(get("/admin/v1/tenants").header("Authorization", tenant.bearer()))
				.andExpect(status().isForbidden());
	}

	@Test
	void suspendedTenantIs403() throws Exception {
		Provisioned tenant = provisionTenant(90, 600);
		jdbc.update("update tenants set status = 'SUSPENDED' where id = ?", tenant.tenantId);
		mvc.perform(get("/v1/emails/" + UUID.randomUUID()).header("Authorization", tenant.bearer()))
				.andExpect(status().isForbidden());
	}

	// --- submission -----------------------------------------------------

	@Test
	void acceptedSubmissionIs202AndQueryable() throws Exception {
		Provisioned tenant = provisionTenant(90, 600);

		MvcResult accepted = mvc.perform(sendEmail(tenant, "player@example.com", null))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.deduplicated").value(false))
				.andReturn();
		String id = json.readTree(accepted.getResponse().getContentAsString()).get("id").asText();

		mvc.perform(get("/v1/emails/" + id).header("Authorization", tenant.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.attemptCount").value(0));

		// Another tenant's key cannot see it.
		Provisioned other = provisionTenant(90, 600);
		mvc.perform(get("/v1/emails/" + id).header("Authorization", other.bearer()))
				.andExpect(status().isNotFound());
	}

	@Test
	void idempotencyKeyReplayReturnsTheOriginal() throws Exception {
		Provisioned tenant = provisionTenant(90, 0);

		String first = json.readTree(mvc.perform(sendEmail(tenant, "player@example.com", "invite:42"))
				.andExpect(status().isAccepted())
				.andReturn().getResponse().getContentAsString()).get("id").asText();

		mvc.perform(sendEmail(tenant, "player@example.com", "invite:42"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.id").value(first))
				.andExpect(jsonPath("$.deduplicated").value(true));
	}

	@Test
	void fromOverrideMustBeAVerifiedIdentity() throws Exception {
		Provisioned tenant = provisionTenant(90, 0);

		// Any address at the operator-trusted domain works.
		mvc.perform(post("/v1/emails")
				.header("Authorization", tenant.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"to\":\"a@example.com\",\"subject\":\"Hello\",\"html\":\"<p>Hi</p>\","
						+ "\"text\":\"Hi\",\"from\":\"Billing <billing@acme.example>\"}"))
				.andExpect(status().isAccepted());

		// A domain the tenant never verified is rejected up front.
		mvc.perform(post("/v1/emails")
				.header("Authorization", tenant.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"to\":\"b@example.com\",\"subject\":\"Hello\",\"html\":\"<p>Hi</p>\","
						+ "\"text\":\"Hi\",\"from\":\"spoof@other.example\"}"))
				.andExpect(status().is(422))
				.andExpect(jsonPath("$.type").value("/errors/sender-not-verified"))
				.andExpect(jsonPath("$.from").value("spoof@other.example"));
	}

	@Test
	void aFromCarryingASecondAddressIsRefused() throws Exception {
		Provisioned tenant = provisionTenant(90, 0);

		// The bypass this guards: addrSpec reads the last angle-addr, a mail
		// parser may take the first. Verifying one and sending the other would
		// let any tenant mail as anybody through the operator's account.
		mvc.perform(post("/v1/emails")
				.header("Authorization", tenant.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"to\":\"a@example.com\",\"subject\":\"Hello\",\"html\":\"<p>Hi</p>\",\"text\":\"Hi\","
						+ "\"from\":\"X <spoof@other.example> <billing@acme.example>\"}"))
				.andExpect(status().is(422))
				.andExpect(jsonPath("$.type").value("/errors/sender-not-verified"));

		// An address sitting bare in the display name is quoted, not trusted,
		// so what goes out can only be read one way.
		MvcResult accepted = mvc.perform(post("/v1/emails")
				.header("Authorization", tenant.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"to\":\"b@example.com\",\"subject\":\"Hello\",\"html\":\"<p>Hi</p>\",\"text\":\"Hi\","
						+ "\"from\":\"spoof@other.example <billing@acme.example>\"}"))
				.andExpect(status().isAccepted())
				.andReturn();
		UUID messageId = UUID.fromString(
				json.readTree(accepted.getResponse().getContentAsString()).get("id").asText());
		assertThat(jdbc.queryForObject("select sender from messages where id = ?", String.class,
				messageId)).isEqualTo("\"spoof@other.example\" <billing@acme.example>");
	}

	@Test
	void aBlankFromAddressAlsoMeansTheSharedTier() throws Exception {
		String slug = "blank-" + SLUGS.incrementAndGet();
		mvc.perform(post("/admin/v1/tenants")
				.header("Authorization", ADMIN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"slug":"%s","name":"Blanky","email":{"fromAddress":"","dailyLimit":90,
						"recipientCooldownSeconds":0}}
						""".formatted(slug)))
				.andExpect(status().isCreated());

		// Blank used to be stored verbatim, leaving a tenant that could never
		// send: no address resolves to an identity.
		assertThat(jdbc.queryForObject(
				"select s.from_address from tenant_email_settings s join tenants t on t.id = s.tenant_id "
						+ "where t.slug = ?", String.class, slug))
				.isEqualTo("Blanky <" + slug + "@send.test.example>");
	}

	@Test
	void tenantWithoutFromAddressLandsOnTheSharedTier() throws Exception {
		String slug = "shared-" + SLUGS.incrementAndGet();
		MvcResult created = mvc.perform(post("/admin/v1/tenants")
				.header("Authorization", ADMIN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"slug":"%s","name":"Sharey","email":{"dailyLimit":90,"recipientCooldownSeconds":0}}
						""".formatted(slug)))
				.andExpect(status().isCreated())
				.andReturn();
		UUID tenantId = UUID.fromString(
				json.readTree(created.getResponse().getContentAsString()).get("id").asText());
		MvcResult issued = mvc.perform(post("/admin/v1/tenants/" + tenantId + "/api-keys")
				.header("Authorization", ADMIN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"label\":\"test\"}"))
				.andExpect(status().isCreated())
				.andReturn();
		String bearer = "Bearer " + json.readTree(issued.getResponse().getContentAsString()).get("apiKey").asText();

		// No from in the request: the shared default applies and is verified.
		mvc.perform(post("/v1/emails")
				.header("Authorization", bearer)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"to\":\"a@example.com\",\"subject\":\"Hello\",\"html\":\"<p>Hi</p>\",\"text\":\"Hi\"}"))
				.andExpect(status().isAccepted());

		// The tenant's own shared address works as an explicit from too.
		mvc.perform(post("/v1/emails")
				.header("Authorization", bearer)
				.contentType(MediaType.APPLICATION_JSON)
				.content(("{\"to\":\"b@example.com\",\"subject\":\"Hello\",\"html\":\"<p>Hi</p>\",\"text\":\"Hi\","
						+ "\"from\":\"%s@send.test.example\"}").formatted(slug)))
				.andExpect(status().isAccepted());

		// Another tenant's address on the same root does not: the shared tier
		// grants one mailbox, never the whole domain.
		mvc.perform(post("/v1/emails")
				.header("Authorization", bearer)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"to\":\"c@example.com\",\"subject\":\"Hello\",\"html\":\"<p>Hi</p>\",\"text\":\"Hi\","
						+ "\"from\":\"someone-else@send.test.example\"}"))
				.andExpect(status().is(422))
				.andExpect(jsonPath("$.type").value("/errors/sender-not-verified"));
	}

	@Test
	void malformedBodiesAreRejectedUpFront() throws Exception {
		Provisioned tenant = provisionTenant(90, 600);
		mvc.perform(post("/v1/emails")
				.header("Authorization", tenant.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"to\":\"not-an-email\",\"subject\":\"\",\"html\":\"x\",\"text\":\"y\"}"))
				.andExpect(status().is(422))
				.andExpect(jsonPath("$.errors").isArray());
	}

	// --- quotas ---------------------------------------------------------

	@Test
	void recipientCooldownCountsMailboxesNotSpellings() throws Exception {
		Provisioned tenant = provisionTenant(90, 600);

		mvc.perform(sendEmail(tenant, "vitor.ramos@gmail.com", null)).andExpect(status().isAccepted());

		// Different spelling, same Gmail mailbox.
		mvc.perform(sendEmail(tenant, "vitorramos+match@gmail.com", null))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().exists("Retry-After"))
				.andExpect(jsonPath("$.reason").value("recipient-cooldown"))
				.andExpect(jsonPath("$.retryAfterSeconds").isNumber());

		// A different mailbox is unaffected.
		mvc.perform(sendEmail(tenant, "someone.else@example.com", null)).andExpect(status().isAccepted());
	}

	@Test
	void limitKeyCapsApplyPerKeyWithinThePrefix() throws Exception {
		Provisioned tenant = provisionTenant(90, 0);
		mvc.perform(put("/admin/v1/tenants/" + tenant.tenantId + "/limit-policies")
				.header("Authorization", ADMIN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("[{\"keyPrefix\":\"inviter\",\"dailyCap\":1}]"))
				.andExpect(status().isOk());

		mvc.perform(sendEmail(tenant, "a@example.com", null, "inviter:7")).andExpect(status().isAccepted());

		mvc.perform(sendEmail(tenant, "b@example.com", null, "inviter:7"))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.reason").value("limit-key-exceeded"))
				.andExpect(jsonPath("$.limitKey").value("inviter:7"));

		// A different key under the same prefix has its own budget; a key with
		// no policy is not limited.
		mvc.perform(sendEmail(tenant, "c@example.com", null, "inviter:8")).andExpect(status().isAccepted());
		mvc.perform(sendEmail(tenant, "d@example.com", null, "campaign:1")).andExpect(status().isAccepted());
	}

	@Test
	void tenantDailyLimitIsTheLastGate() throws Exception {
		Provisioned tenant = provisionTenant(2, 0);
		mvc.perform(sendEmail(tenant, "a@example.com", null)).andExpect(status().isAccepted());
		mvc.perform(sendEmail(tenant, "b@example.com", null)).andExpect(status().isAccepted());
		mvc.perform(sendEmail(tenant, "c@example.com", null))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.reason").value("tenant-daily-limit"));
	}

	@Test
	void rejectionOrderIsContractual() throws Exception {
		// Recipient cooldown must win over the limit key when both would trip.
		Provisioned tenant = provisionTenant(90, 600);
		mvc.perform(put("/admin/v1/tenants/" + tenant.tenantId + "/limit-policies")
				.header("Authorization", ADMIN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("[{\"keyPrefix\":\"inviter\",\"dailyCap\":1}]"))
				.andExpect(status().isOk());

		mvc.perform(sendEmail(tenant, "same@example.com", null, "inviter:1")).andExpect(status().isAccepted());
		mvc.perform(sendEmail(tenant, "same@example.com", null, "inviter:1"))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.reason").value("recipient-cooldown"));
	}

	@Test
	void rejectedRequestsDoNotConsumeQuota() throws Exception {
		Provisioned tenant = provisionTenant(2, 600);
		mvc.perform(sendEmail(tenant, "a@example.com", null)).andExpect(status().isAccepted());
		// Burn several rejections on the cooldown...
		for (int i = 0; i < 3; i++) {
			mvc.perform(sendEmail(tenant, "a@example.com", null)).andExpect(status().isTooManyRequests());
		}
		// ...and the daily budget still has room for a second mailbox.
		mvc.perform(sendEmail(tenant, "b@example.com", null)).andExpect(status().isAccepted());
	}

	// --- helpers --------------------------------------------------------

	private record Provisioned(UUID tenantId, UUID keyId, String apiKey) {

		String bearer() {
			return "Bearer " + apiKey;
		}
	}

	private Provisioned provisionTenant(int dailyLimit, int cooldownSeconds) throws Exception {
		String slug = "acme-" + SLUGS.incrementAndGet();
		MvcResult created = mvc.perform(post("/admin/v1/tenants")
				.header("Authorization", ADMIN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"slug":"%s","name":"Acme","email":{"fromAddress":"Acme <mail@acme.example>",
						"dailyLimit":%d,"recipientCooldownSeconds":%d}}
						""".formatted(slug, dailyLimit, cooldownSeconds)))
				.andExpect(status().isCreated())
				.andReturn();
		UUID tenantId = UUID.fromString(
				json.readTree(created.getResponse().getContentAsString()).get("id").asText());

		MvcResult issued = mvc.perform(post("/admin/v1/tenants/" + tenantId + "/api-keys")
				.header("Authorization", ADMIN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"label\":\"test\"}"))
				.andExpect(status().isCreated())
				.andReturn();
		JsonNode key = json.readTree(issued.getResponse().getContentAsString());
		return new Provisioned(tenantId, UUID.fromString(key.get("id").asText()), key.get("apiKey").asText());
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder sendEmail(
			Provisioned tenant, String to, String idempotencyKey, String... limitKeys) throws Exception {
		var body = json.createObjectNode()
				.put("to", to)
				.put("subject", "Hello")
				.put("html", "<p>Hi</p>")
				.put("text", "Hi");
		if (idempotencyKey != null) {
			body.put("idempotencyKey", idempotencyKey);
		}
		if (limitKeys.length > 0) {
			var keys = body.putArray("limitKeys");
			for (String limitKey : limitKeys) {
				keys.add(limitKey);
			}
		}
		return post("/v1/emails")
				.header("Authorization", tenant.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body.toString());
	}
}
