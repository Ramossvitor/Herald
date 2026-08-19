package io.github.ramossvitor.herald;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import io.github.ramossvitor.herald.sender.SenderIdentityVerifier;

/**
 * The custom-domain lifecycle end to end: register → DNS records back →
 * poller flips the status → the domain becomes usable as a from.
 *
 * WireMock's DSL is qualified rather than statically imported: half its
 * verbs (get, post, delete) collide with MockMvc's.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
		"herald.admin-api-key=test-admin-master-key",
		"herald.email.shared-root-domain=send.test.example",
		"herald.resend.api-key=re_test_fake",
		"herald.outbox.poll-interval=1h",
})
class SenderIdentityIntegrationTest {

	private static final String ADMIN = "Bearer test-admin-master-key";
	private static final WireMockServer RESEND = new WireMockServer(
			WireMockConfiguration.wireMockConfig().dynamicPort());
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
	private MockMvc mvc;

	@Autowired
	private SenderIdentityVerifier verifier;

	@Autowired
	private JdbcTemplate jdbc;

	// Deliberately not the context's mapper: Boot 4 wires Jackson 3 there, and
	// these tests only need a local JSON parser.
	private final ObjectMapper json = new ObjectMapper();

	@BeforeEach
	void resetProvider() {
		RESEND.resetAll();
		// Self-service identities are globally unique on (channel, identifier);
		// wipe them so each test can reuse simple domain names.
		jdbc.update("delete from sender_identities where provider_ref is not null");
	}

	@Test
	void registerReturnsTheProvidersDnsRecords() throws Exception {
		RESEND.stubFor(WireMock.post(WireMock.urlEqualTo("/domains")).willReturn(WireMock.okJson(
				"{\"id\":\"dom_1\",\"status\":\"not_started\","
						+ "\"records\":[{\"record\":\"DKIM\",\"name\":\"resend._domainkey\",\"value\":\"p=abc\"}]}")));
		Provisioned tenant = provisionTenant();

		mvc.perform(post("/v1/sender-identities")
				.header("Authorization", tenant.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"domain\":\"Client.Example\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.identifier").value("client.example"))
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.dnsRecords[0].record").value("DKIM"))
				.andExpect(jsonPath("$.dnsRecords[0].value").value("p=abc"));

		RESEND.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/domains"))
				.withHeader("Authorization", WireMock.equalTo("Bearer re_test_fake"))
				.withRequestBody(WireMock.matchingJsonPath("$.name", WireMock.equalTo("client.example"))));
	}

	@Test
	void registrationRefusedByTheProviderLeavesNothingBehind() throws Exception {
		RESEND.stubFor(WireMock.post(WireMock.urlEqualTo("/domains"))
				.willReturn(WireMock.aResponse().withStatus(422).withBody("{\"name\":\"validation_error\"}")));
		Provisioned tenant = provisionTenant();

		mvc.perform(post("/v1/sender-identities")
				.header("Authorization", tenant.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"domain\":\"rejected.example\"}"))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.type").value("/errors/provider-unavailable"));

		// No half-registered row blocking a retry.
		mvc.perform(get("/v1/sender-identities").header("Authorization", tenant.bearer()))
				.andExpect(jsonPath("$[?(@.identifier == 'rejected.example')]").isEmpty());
	}

	@Test
	void verifyIsPolledUntilTheProviderConfirms() throws Exception {
		RESEND.stubFor(WireMock.post(WireMock.urlEqualTo("/domains"))
				.willReturn(WireMock.okJson("{\"id\":\"dom_2\",\"status\":\"not_started\",\"records\":[]}")));
		RESEND.stubFor(WireMock.post(WireMock.urlEqualTo("/domains/dom_2/verify")).willReturn(WireMock.okJson("{}")));
		RESEND.stubFor(WireMock.get(WireMock.urlEqualTo("/domains/dom_2")).inScenario("dns")
				.whenScenarioStateIs(Scenario.STARTED)
				.willReturn(WireMock.okJson("{\"id\":\"dom_2\",\"status\":\"pending\"}"))
				.willSetStateTo("propagated"));
		RESEND.stubFor(WireMock.get(WireMock.urlEqualTo("/domains/dom_2")).inScenario("dns")
				.whenScenarioStateIs("propagated")
				.willReturn(WireMock.okJson("{\"id\":\"dom_2\",\"status\":\"verified\"}")));

		Provisioned tenant = provisionTenant();
		String id = register(tenant, "client-two.example");

		mvc.perform(post("/v1/sender-identities/" + id + "/verify").header("Authorization", tenant.bearer()))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("VERIFYING"));

		// Not usable while the provider has not confirmed DNS.
		mvc.perform(sendFrom(tenant, "early@example.com", "news@client-two.example"))
				.andExpect(status().is(422));

		// First check: the provider still sees pending DNS.
		forceCheckDue(id);
		verifier.runOnce();
		assertStatus(tenant, id, "VERIFYING");

		// Second check: propagated.
		forceCheckDue(id);
		verifier.runOnce();
		assertStatus(tenant, id, "VERIFIED");

		// Any address at the domain is now a usable from.
		mvc.perform(sendFrom(tenant, "player@example.com", "news@client-two.example"))
				.andExpect(status().isAccepted());
	}

	@Test
	void failedVerificationIsTerminalWithAnError() throws Exception {
		RESEND.stubFor(WireMock.post(WireMock.urlEqualTo("/domains"))
				.willReturn(WireMock.okJson("{\"id\":\"dom_3\",\"status\":\"not_started\",\"records\":[]}")));
		RESEND.stubFor(WireMock.post(WireMock.urlEqualTo("/domains/dom_3/verify")).willReturn(WireMock.okJson("{}")));
		RESEND.stubFor(WireMock.get(WireMock.urlEqualTo("/domains/dom_3"))
				.willReturn(WireMock.okJson("{\"id\":\"dom_3\",\"status\":\"failed\"}")));

		Provisioned tenant = provisionTenant();
		String id = register(tenant, "client-three.example");
		mvc.perform(post("/v1/sender-identities/" + id + "/verify").header("Authorization", tenant.bearer()))
				.andExpect(status().isAccepted());

		forceCheckDue(id);
		verifier.runOnce();

		assertStatus(tenant, id, "FAILED");
		mvc.perform(get("/v1/sender-identities").header("Authorization", tenant.bearer()))
				.andExpect(jsonPath("$[?(@.id == '%s')].lastError".formatted(id))
						.value("provider reported failed verification"));
	}

	@Test
	void aDomainCanOnlyBeClaimedOnce() throws Exception {
		RESEND.stubFor(WireMock.post(WireMock.urlEqualTo("/domains"))
				.willReturn(WireMock.okJson("{\"id\":\"dom_4\",\"status\":\"not_started\",\"records\":[]}")));
		Provisioned first = provisionTenant();
		register(first, "contested.example");

		Provisioned second = provisionTenant();
		mvc.perform(post("/v1/sender-identities")
				.header("Authorization", second.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"domain\":\"contested.example\"}"))
				.andExpect(status().isConflict());
	}

	@Test
	void identitiesAreScopedToTheirTenant() throws Exception {
		RESEND.stubFor(WireMock.post(WireMock.urlEqualTo("/domains"))
				.willReturn(WireMock.okJson("{\"id\":\"dom_5\",\"status\":\"not_started\",\"records\":[]}")));
		Provisioned owner = provisionTenant();
		String id = register(owner, "scoped.example");

		Provisioned intruder = provisionTenant();
		mvc.perform(post("/v1/sender-identities/" + id + "/verify").header("Authorization", intruder.bearer()))
				.andExpect(status().isNotFound());
		mvc.perform(get("/v1/sender-identities").header("Authorization", intruder.bearer()))
				.andExpect(jsonPath("$[?(@.identifier == 'scoped.example')]").isEmpty());
	}

	@Test
	void deleteRemovesTheIdentityHereAndAtTheProvider() throws Exception {
		RESEND.stubFor(WireMock.post(WireMock.urlEqualTo("/domains"))
				.willReturn(WireMock.okJson("{\"id\":\"dom_6\",\"status\":\"not_started\",\"records\":[]}")));
		RESEND.stubFor(WireMock.delete(WireMock.urlEqualTo("/domains/dom_6")).willReturn(WireMock.okJson("{}")));
		Provisioned tenant = provisionTenant();
		String id = register(tenant, "doomed.example");

		mvc.perform(delete("/v1/sender-identities/" + id).header("Authorization", tenant.bearer()))
				.andExpect(status().isNoContent());

		RESEND.verify(WireMock.deleteRequestedFor(WireMock.urlEqualTo("/domains/dom_6")));
		mvc.perform(get("/v1/sender-identities").header("Authorization", tenant.bearer()))
				.andExpect(jsonPath("$[?(@.id == '%s')]".formatted(id)).isEmpty());
	}

	@Test
	void sharedRootSubdomainsAreReservedForTheOperator() throws Exception {
		Provisioned tenant = provisionTenant();
		mvc.perform(post("/v1/sender-identities")
				.header("Authorization", tenant.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"domain\":\"anything.send.test.example\"}"))
				.andExpect(status().isConflict());
		mvc.perform(post("/v1/sender-identities")
				.header("Authorization", tenant.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"domain\":\"send.test.example\"}"))
				.andExpect(status().isConflict());
	}

	@Test
	void operatorCanProvisionADedicatedSubdomainOfTheSharedRoot() throws Exception {
		RESEND.stubFor(WireMock.post(WireMock.urlEqualTo("/domains"))
				.willReturn(WireMock.okJson("{\"id\":\"dom_8\",\"status\":\"not_started\","
						+ "\"records\":[{\"record\":\"DKIM\",\"name\":\"resend._domainkey\"}]}")));
		Provisioned tenant = provisionTenant();

		mvc.perform(post("/admin/v1/tenants/" + tenant.tenantId + "/sender-identities")
				.header("Authorization", ADMIN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"domain\":\"dedicated.send.test.example\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.identifier").value("dedicated.send.test.example"));
	}

	@Test
	void adminDrivesTheSameLifecycleForAnyTenant() throws Exception {
		RESEND.stubFor(WireMock.post(WireMock.urlEqualTo("/domains"))
				.willReturn(WireMock.okJson("{\"id\":\"dom_7\",\"status\":\"not_started\",\"records\":[]}")));
		Provisioned tenant = provisionTenant();

		mvc.perform(post("/admin/v1/tenants/" + tenant.tenantId + "/sender-identities")
				.header("Authorization", ADMIN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"domain\":\"operated.example\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.identifier").value("operated.example"));

		mvc.perform(get("/admin/v1/tenants/" + tenant.tenantId + "/sender-identities")
				.header("Authorization", ADMIN))
				.andExpect(jsonPath("$[?(@.identifier == 'operated.example')]").isNotEmpty());

		// A tenant key cannot reach the admin surface.
		mvc.perform(get("/admin/v1/tenants/" + tenant.tenantId + "/sender-identities")
				.header("Authorization", tenant.bearer()))
				.andExpect(status().isForbidden());
	}

	// --- helpers --------------------------------------------------------

	private record Provisioned(UUID tenantId, String apiKey) {

		String bearer() {
			return "Bearer " + apiKey;
		}
	}

	private Provisioned provisionTenant() throws Exception {
		String slug = "sender-" + SLUGS.incrementAndGet();
		MvcResult created = mvc.perform(post("/admin/v1/tenants")
				.header("Authorization", ADMIN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"slug":"%s","name":"Acme","email":{"fromAddress":"Acme <mail@acme.example>",
						"dailyLimit":500,"recipientCooldownSeconds":0}}
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
		JsonNode key = json.readTree(issued.getResponse().getContentAsString());
		return new Provisioned(tenantId, key.get("apiKey").asText());
	}

	private String register(Provisioned tenant, String domain) throws Exception {
		MvcResult result = mvc.perform(post("/v1/sender-identities")
				.header("Authorization", tenant.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"domain\":\"%s\"}".formatted(domain)))
				.andExpect(status().isCreated())
				.andReturn();
		return json.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder sendFrom(
			Provisioned tenant, String to, String from) {
		return post("/v1/emails")
				.header("Authorization", tenant.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content(("{\"to\":\"%s\",\"subject\":\"Hello\",\"html\":\"<p>Hi</p>\",\"text\":\"Hi\","
						+ "\"from\":\"%s\"}").formatted(to, from));
	}

	private void forceCheckDue(String id) {
		jdbc.update("update sender_identities set next_check_at = now() - interval '1 second' where id = ?",
				UUID.fromString(id));
	}

	private void assertStatus(Provisioned tenant, String id, String expected) throws Exception {
		mvc.perform(get("/v1/sender-identities").header("Authorization", tenant.bearer()))
				.andExpect(jsonPath("$[?(@.id == '%s')].status".formatted(id)).value(expected));
	}
}
