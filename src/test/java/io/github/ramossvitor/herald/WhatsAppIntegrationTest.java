package io.github.ramossvitor.herald;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
// get/post come from MockMvc here; WireMock's namesakes are qualified.
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import io.github.ramossvitor.herald.outbox.MessageRepository;
import io.github.ramossvitor.herald.outbox.MessageStatus;
import io.github.ramossvitor.herald.outbox.OutboxWorker;
import io.github.ramossvitor.herald.sender.Channel;
import io.github.ramossvitor.herald.whatsapp.WhatsAppTemplateSync;

/**
 * The WhatsApp channel end to end: a tenant's own credentials go in, a template
 * gate decides what may be queued, dispatch runs under those credentials, and
 * Meta reports back what actually happened.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
		"herald.admin-api-key=test-admin-master-key",
		"herald.secret-key=aGVyYWxkLXRlc3Qta2V5LTAxMjM0NTY3ODlhYmNkZWY=",
		"herald.email.shared-root-domain=send.test.example",
		"herald.resend.api-key=",
		"herald.whatsapp.webhook-verify-token=the-verify-token",
		"herald.whatsapp.send-interval=0ms",
		"herald.whatsapp.template-sync-interval=1h",
		"herald.outbox.poll-interval=1h",
})
class WhatsAppIntegrationTest {

	private static final WireMockServer META = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
	private static final AtomicInteger SLUGS = new AtomicInteger();
	private static final String ADMIN = "Bearer test-admin-master-key";
	private static final String APP_SECRET = "the-tenant-app-secret";
	private static final String TOKEN = "EAAsecrettokenvalue";

	@DynamicPropertySource
	static void metaEndpoint(DynamicPropertyRegistry registry) {
		META.start();
		registry.add("herald.whatsapp.base-url", META::baseUrl);
	}

	@AfterAll
	static void stopWireMock() {
		META.stop();
	}

	@Autowired
	private MockMvc mvc;

	@Autowired
	private OutboxWorker worker;

	@Autowired
	private MessageRepository messages;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private WhatsAppTemplateSync templateSync;

	private final ObjectMapper json = new ObjectMapper();

	@BeforeEach
	void reset() {
		META.resetAll();
		jdbc.update("delete from messages");
	}

	// --- onboarding -----------------------------------------------------

	@Test
	void registeringCredentialsProvesThemAndMirrorsTheTemplates() throws Exception {
		stubPhoneNumber();
		stubTemplates();
		Tenant tenant = newTenant();

		MvcResult registered = register(tenant)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("VERIFIED"))
				.andExpect(jsonPath("$.phoneNumberId").value("15550001111"))
				.andReturn();

		// The credentials went in and can never come back out.
		String body = registered.getResponse().getContentAsString();
		assertThat(body).doesNotContain(TOKEN).doesNotContain(APP_SECRET);

		// Stored encrypted, not in the clear.
		String stored = jdbc.queryForObject("select access_token from tenant_whatsapp_settings where tenant_id = ?",
				String.class, tenant.id);
		assertThat(stored).startsWith("v1:").doesNotContain(TOKEN);

		mvc.perform(get("/v1/whatsapp-templates").header("Authorization", tenant.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].name").value("order_update"))
				.andExpect(jsonPath("$[0].status").value("APPROVED"))
				.andExpect(jsonPath("$[0].paramCount").value(2));
	}

	@Test
	void credentialsMetaRefusesAreStoredAsFailed() throws Exception {
		META.stubFor(WireMock.get(urlPathMatching(".*/15550001111"))
				.willReturn(aResponse().withStatus(401)
						.withBody("{\"error\":{\"message\":\"Invalid OAuth access token\",\"code\":190}}")));
		Tenant tenant = newTenant();

		register(tenant)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("FAILED"))
				.andExpect(jsonPath("$.lastError").value("http 401 code 190: Invalid OAuth access token"));
	}

	@Test
	void twoTenantsCannotClaimOneBusinessAccount() throws Exception {
		stubPhoneNumber();
		stubTemplates();
		Tenant first = newTenant();
		register(first, "shared-waba").andExpect(status().isCreated());

		// The webhook routes by WABA id alone; two owners would make an inbound
		// receipt ambiguous.
		Tenant second = newTenant();
		register(second, "shared-waba").andExpect(status().isConflict());
	}

	// --- the template gate ----------------------------------------------

	@Test
	void anApprovedTemplateWithTheRightArityIsAccepted() throws Exception {
		Tenant tenant = onboarded();

		mvc.perform(send(tenant, "{\"to\":\"+55 11 99999-0000\",\"template\":\"order_update\","
				+ "\"language\":\"pt_BR\",\"params\":[\"Acme\",\"42\"]}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("PENDING"));
	}

	@Test
	void aTemplateMetaDoesNotKnowIsRefusedAtTheDoor() throws Exception {
		Tenant tenant = onboarded();

		mvc.perform(send(tenant, "{\"to\":\"+5511999990000\",\"template\":\"no_such_template\","
				+ "\"language\":\"pt_BR\",\"params\":[]}"))
				.andExpect(status().is(422))
				.andExpect(jsonPath("$.type").value("/errors/template-not-usable"))
				.andExpect(jsonPath("$.template").value("no_such_template"));

		assertThat(messages.count()).isZero();
	}

	@Test
	void anUnapprovedTemplateIsRefusedWithItsReason() throws Exception {
		Tenant tenant = onboarded();

		mvc.perform(send(tenant, "{\"to\":\"+5511999990000\",\"template\":\"promo\","
				+ "\"language\":\"pt_BR\",\"params\":[]}"))
				.andExpect(status().is(422))
				.andExpect(jsonPath("$.type").value("/errors/template-not-usable"))
				.andExpect(jsonPath("$.templateStatus").value("REJECTED"))
				.andExpect(jsonPath("$.reason").value(containsString("INVALID_FORMAT")));
	}

	@Test
	void theWrongNumberOfArgumentsIsRefused() throws Exception {
		// Meta fills placeholders by position, so a short list does not omit the
		// last blank — it shifts every value into the wrong one.
		Tenant tenant = onboarded();

		mvc.perform(send(tenant, "{\"to\":\"+5511999990000\",\"template\":\"order_update\","
				+ "\"language\":\"pt_BR\",\"params\":[\"Acme\"]}"))
				.andExpect(status().is(422))
				.andExpect(jsonPath("$.reason").value(containsString("takes 2 parameters")));
	}

	@Test
	void aNumberWithoutACountryCodeIsRefused() throws Exception {
		Tenant tenant = onboarded();

		// Shape passes the regex; meaning does not. Guessing here would deliver
		// to a stranger and bill for it.
		mvc.perform(send(tenant, "{\"to\":\"+011999990000\",\"template\":\"order_update\","
				+ "\"language\":\"pt_BR\",\"params\":[\"Acme\",\"42\"]}"))
				.andExpect(status().is(422))
				.andExpect(jsonPath("$.type").value("/errors/invalid-recipient"));
	}

	@Test
	void aTenantWithoutCredentialsIsToldSo() throws Exception {
		Tenant tenant = newTenant();

		mvc.perform(send(tenant, "{\"to\":\"+5511999990000\",\"template\":\"order_update\","
				+ "\"language\":\"pt_BR\",\"params\":[]}"))
				.andExpect(status().is(422))
				.andExpect(jsonPath("$.type").value("/errors/whatsapp-not-configured"));
	}

	// --- dispatch -------------------------------------------------------

	@Test
	void dispatchSendsUnderTheTenantsOwnNumberAndCredentials() throws Exception {
		Tenant tenant = onboarded();
		META.stubFor(WireMock.post(urlPathMatching(".*/15550001111/messages")).willReturn(
				okJson("{\"messaging_product\":\"whatsapp\",\"messages\":[{\"id\":\"wamid.SENT1\"}]}")));

		UUID id = accept(tenant, "{\"to\":\"+5511999990000\",\"template\":\"order_update\","
				+ "\"language\":\"pt_BR\",\"params\":[\"Acme\",\"42\"]}");

		worker.runOnce(Channel.WHATSAPP);

		assertThat(messages.findById(id).orElseThrow().getStatus()).isEqualTo(MessageStatus.SENT);
		assertThat(messages.findById(id).orElseThrow().getProviderMessageId()).isEqualTo("wamid.SENT1");

		META.verify(postRequestedFor(urlPathMatching(".*/15550001111/messages"))
				.withHeader("Authorization", equalTo("Bearer " + TOKEN))
				.withRequestBody(matchingJsonPath("$.messaging_product", equalTo("whatsapp")))
				// The canonical number, not the spelling the caller typed.
				.withRequestBody(matchingJsonPath("$.to", equalTo("5511999990000")))
				.withRequestBody(matchingJsonPath("$.template.name", equalTo("order_update")))
				.withRequestBody(matchingJsonPath("$.template.language.code", equalTo("pt_BR")))
				.withRequestBody(matchingJsonPath("$.template.components[0].parameters[0].text", equalTo("Acme")))
				.withRequestBody(matchingJsonPath("$.template.components[0].parameters[1].text", equalTo("42"))));
	}

	@Test
	void aProviderRejectionNeverCarriesTheCredentialIntoTheRow() throws Exception {
		// last_error is persisted and handed back by the status endpoint. A
		// provider failure is exactly where a careless implementation leaks.
		Tenant tenant = onboarded();
		META.stubFor(WireMock.post(urlPathMatching(".*/messages")).willReturn(aResponse().withStatus(400)
				.withBody("{\"error\":{\"message\":\"Template does not exist\",\"code\":132001,"
						+ "\"fbtrace_id\":\"Axyz\"}}")));

		UUID id = accept(tenant, "{\"to\":\"+5511999990000\",\"template\":\"order_update\","
				+ "\"language\":\"pt_BR\",\"params\":[\"Acme\",\"42\"]}");
		worker.runOnce(Channel.WHATSAPP);

		MvcResult status = mvc.perform(get("/v1/whatsapp-messages/" + id).header("Authorization", tenant.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FAILED"))
				.andReturn();
		String body = status.getResponse().getContentAsString();
		assertThat(body).contains("132001").doesNotContain(TOKEN).doesNotContain(APP_SECRET);
	}

	// --- webhook --------------------------------------------------------

	@Test
	void theSubscriptionHandshakeAnswersOnlyTheConfiguredToken() throws Exception {
		mvc.perform(get("/webhooks/whatsapp")
				.param("hub.mode", "subscribe")
				.param("hub.verify_token", "the-verify-token")
				.param("hub.challenge", "challenge-123"))
				.andExpect(status().isOk())
				.andExpect(content().string("challenge-123"));

		mvc.perform(get("/webhooks/whatsapp")
				.param("hub.mode", "subscribe")
				.param("hub.verify_token", "not-the-token")
				.param("hub.challenge", "challenge-123"))
				.andExpect(status().isForbidden());
	}

	@Test
	void aSignedFailureReceiptTurnsSentIntoFailed() throws Exception {
		// A 200 from Meta means "accepted", not "delivered". Without the receipt
		// a number that never receives anything looks identical to one that does.
		Tenant tenant = onboarded();
		META.stubFor(WireMock.post(urlPathMatching(".*/messages")).willReturn(
				okJson("{\"messages\":[{\"id\":\"wamid.DELIVERFAIL\"}]}")));
		UUID id = accept(tenant, "{\"to\":\"+5511999990000\",\"template\":\"order_update\","
				+ "\"language\":\"pt_BR\",\"params\":[\"Acme\",\"42\"]}");
		worker.runOnce(Channel.WHATSAPP);
		assertThat(messages.findById(id).orElseThrow().getStatus()).isEqualTo(MessageStatus.SENT);

		byte[] payload = failureReceipt(tenant.wabaId, "wamid.DELIVERFAIL");
		mvc.perform(post("/webhooks/whatsapp")
				.contentType(MediaType.APPLICATION_JSON)
				.header("X-Hub-Signature-256", sign(payload, APP_SECRET))
				.content(payload))
				.andExpect(status().isOk());

		assertThat(messages.findById(id).orElseThrow().getStatus()).isEqualTo(MessageStatus.FAILED);
		assertThat(messages.findById(id).orElseThrow().getLastError())
				.isEqualTo("delivery failed code 131026: Message undeliverable");
	}

	@Test
	void anUnsignedOrMissignedPayloadChangesNothing() throws Exception {
		Tenant tenant = onboarded();
		META.stubFor(WireMock.post(urlPathMatching(".*/messages")).willReturn(
				okJson("{\"messages\":[{\"id\":\"wamid.KEEP\"}]}")));
		UUID id = accept(tenant, "{\"to\":\"+5511999990000\",\"template\":\"order_update\","
				+ "\"language\":\"pt_BR\",\"params\":[\"Acme\",\"42\"]}");
		worker.runOnce(Channel.WHATSAPP);

		byte[] payload = failureReceipt(tenant.wabaId, "wamid.KEEP");

		// No signature at all.
		mvc.perform(post("/webhooks/whatsapp").contentType(MediaType.APPLICATION_JSON).content(payload))
				.andExpect(status().isForbidden());
		// Signed with something that is not this tenant's app secret.
		mvc.perform(post("/webhooks/whatsapp")
				.contentType(MediaType.APPLICATION_JSON)
				.header("X-Hub-Signature-256", sign(payload, "some-other-app-secret"))
				.content(payload))
				.andExpect(status().isForbidden());
		// Correctly signed, then edited in flight.
		byte[] edited = failureReceipt(tenant.wabaId, "wamid.KEEP");
		mvc.perform(post("/webhooks/whatsapp")
				.contentType(MediaType.APPLICATION_JSON)
				.header("X-Hub-Signature-256", sign(failureReceipt(tenant.wabaId, "wamid.OTHER"), APP_SECRET))
				.content(edited))
				.andExpect(status().isForbidden());

		assertThat(messages.findById(id).orElseThrow().getStatus()).isEqualTo(MessageStatus.SENT);
	}

	@Test
	void oneTenantsSecretCannotActOnAnothersMessages() throws Exception {
		// The heart of a single callback URL serving everyone: the payload names
		// the tenant, and the payload is what has not been verified yet.
		Tenant victim = onboarded();
		META.stubFor(WireMock.post(urlPathMatching(".*/messages")).willReturn(
				okJson("{\"messages\":[{\"id\":\"wamid.VICTIM\"}]}")));
		UUID id = accept(victim, "{\"to\":\"+5511999990000\",\"template\":\"order_update\","
				+ "\"language\":\"pt_BR\",\"params\":[\"Acme\",\"42\"]}");
		worker.runOnce(Channel.WHATSAPP);

		Tenant attacker = onboarded("attacker-app-secret");

		// Attacker signs a payload naming the victim's account, with their own
		// perfectly valid secret.
		byte[] payload = failureReceipt(victim.wabaId, "wamid.VICTIM");
		mvc.perform(post("/webhooks/whatsapp")
				.contentType(MediaType.APPLICATION_JSON)
				.header("X-Hub-Signature-256", sign(payload, "attacker-app-secret"))
				.content(payload))
				.andExpect(status().isForbidden());

		assertThat(messages.findById(id).orElseThrow().getStatus()).isEqualTo(MessageStatus.SENT);
		// On the registered form, not the slug: digits() is what the webhook
		// routes by, so a collision there is the very thing this rules out.
		assertThat(digits(attacker.wabaId)).isNotEqualTo(digits(victim.wabaId));
	}

	@Test
	void aPayloadForAnAccountNobodyOwnsIsAcknowledgedAndDropped() throws Exception {
		// 200 rather than 404: Meta retries anything else, and this callback URL
		// is shared by every tenant, so receipts for a deregistered account would
		// retry until the whole subscription is throttled. Answering as we do for
		// a bad signature would also tell an unauthenticated caller who is a
		// customer.
		byte[] payload = failureReceipt("waba-nobody-registered", "wamid.X");
		mvc.perform(post("/webhooks/whatsapp")
				.contentType(MediaType.APPLICATION_JSON)
				.header("X-Hub-Signature-256", sign(payload, APP_SECRET))
				.content(payload))
				.andExpect(status().isOk());
	}

	@Test
	void receiptsRidingOnAnUnnamedEntryAreNotActedOn() throws Exception {
		Tenant tenant = onboarded();
		META.stubFor(WireMock.post(urlPathMatching(".*/messages")).willReturn(
				okJson("{\"messages\":[{\"id\":\"wamid.SMUGGLED\"}]}")));
		UUID id = accept(tenant, "{\"to\":\"+5511999990000\",\"template\":\"order_update\","
				+ "\"language\":\"pt_BR\",\"params\":[\"Acme\",\"42\"]}");
		worker.runOnce(Channel.WHATSAPP);

		// One named entry the signature covers, plus an anonymous one carrying the
		// receipt. wabaIds() ignores the id-less entry, so the "names exactly one
		// account" guard passes — the statuses must still be scoped to the entry
		// that was actually named.
		byte[] payload = ("""
				{"entry":[{"id":"%s","changes":[]},
				          {"changes":[{"value":{"statuses":[{"id":"wamid.SMUGGLED","status":"failed",
				                                             "errors":[{"code":131026,"title":"undeliverable"}]}]}}]}]}
				""").formatted(digits(tenant.wabaId)).getBytes(StandardCharsets.UTF_8);
		mvc.perform(post("/webhooks/whatsapp")
				.contentType(MediaType.APPLICATION_JSON)
				.header("X-Hub-Signature-256", sign(payload, APP_SECRET))
				.content(payload))
				.andExpect(status().isOk());

		assertThat(messages.findById(id).orElseThrow().getStatus()).isEqualTo(MessageStatus.SENT);
	}

	@Test
	void anOversizedBodyIsRefusedBeforeItIsParsed() throws Exception {
		byte[] payload = new byte[2 * 1024 * 1024];
		java.util.Arrays.fill(payload, (byte) 'x');
		mvc.perform(post("/webhooks/whatsapp")
				.contentType(MediaType.APPLICATION_JSON)
				.content(payload))
				.andExpect(status().isPayloadTooLarge());
	}

	@Test
	void aPayloadNamingSeveralAccountsIsRefused() throws Exception {
		// It would have to be verified against several secrets and acted on
		// across tenants; there is no correct single answer.
		byte[] payload = ("{\"entry\":[{\"id\":\"waba-a\",\"changes\":[]},{\"id\":\"waba-b\",\"changes\":[]}]}")
				.getBytes(StandardCharsets.UTF_8);
		mvc.perform(post("/webhooks/whatsapp")
				.contentType(MediaType.APPLICATION_JSON)
				.header("X-Hub-Signature-256", sign(payload, APP_SECRET))
				.content(payload))
				.andExpect(status().isBadRequest());
	}

	// --- template sync --------------------------------------------------

	@Test
	void aSweepCarriesAReclassifiedTemplateIntoTheMirror() throws Exception {
		Tenant tenant = onboarded();
		assertThat(templateStatus(tenant, "order_update")).isEqualTo("APPROVED");

		// Meta pauses it between sweeps. The refresh has to reach the row: a
		// mirror still reading APPROVED keeps admitting sends Meta will refuse,
		// and the freshness is the only thing the mirror is worth.
		stubTemplates("""
				{"data":[
				  {"id":"t1","name":"order_update","language":"pt_BR","category":"UTILITY","status":"PAUSED",
				   "rejected_reason":"NONE",
				   "components":[{"type":"BODY","text":"Olá {{1}}, pedido {{2}} enviado."}]}
				]}
				""");
		assertThat(templateSync.runOnce()).isPositive();

		assertThat(templateStatus(tenant, "order_update")).isEqualTo("PAUSED");
		mvc.perform(send(tenant, "{\"to\":\"+5511999990000\",\"template\":\"order_update\","
				+ "\"language\":\"pt_BR\",\"params\":[\"Acme\",\"42\"]}"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.templateStatus").value("PAUSED"));
	}

	@Test
	void aSweepDropsTemplatesMetaNoLongerReports() throws Exception {
		Tenant tenant = onboarded();
		assertThat(templateCount(tenant)).isEqualTo(2);

		stubTemplates("""
				{"data":[
				  {"id":"t1","name":"order_update","language":"pt_BR","category":"UTILITY","status":"APPROVED",
				   "rejected_reason":"NONE",
				   "components":[{"type":"BODY","text":"Olá {{1}}, pedido {{2}} enviado."}]}
				]}
				""");
		templateSync.runOnce();

		// A row left behind after a template was deleted upstream would keep
		// passing the submission gate for something that no longer exists.
		assertThat(templateCount(tenant)).isEqualTo(1);
	}

	@Test
	void aPaginatedAnswerLeavesTheMirrorAlone() throws Exception {
		Tenant tenant = onboarded();
		assertThat(templateCount(tenant)).isEqualTo(2);

		// A partial list must never read as "everything else was deleted" — that
		// would take the tenant's whole template set with it.
		stubTemplates("{\"data\":[],\"paging\":{\"next\":\"https://graph.facebook.com/next\"}}");
		templateSync.runOnce();

		assertThat(templateCount(tenant)).isEqualTo(2);
	}

	@Test
	void aTemplateWantingAHeaderParameterIsRefusedAtSubmission() throws Exception {
		stubPhoneNumber();
		// Approved, but the value goes in the header — which Herald never sends,
		// so Meta would reject the delivery after an attempt was spent.
		stubTemplates("""
				{"data":[
				  {"id":"t9","name":"with_header","language":"pt_BR","category":"UTILITY","status":"APPROVED",
				   "rejected_reason":"NONE",
				   "components":[{"type":"HEADER","format":"TEXT","text":"Pedido {{1}}"},
				                 {"type":"BODY","text":"Olá {{1}}."}]}
				]}
				""");
		Tenant tenant = newTenant();
		register(tenant).andExpect(status().isCreated());

		mvc.perform(send(tenant, "{\"to\":\"+5511999990000\",\"template\":\"with_header\","
				+ "\"language\":\"pt_BR\",\"params\":[\"Acme\"]}"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.reason").value(containsString("header")));
	}

	// --- helpers --------------------------------------------------------

	private String templateStatus(Tenant tenant, String name) {
		return jdbc.queryForObject(
				"select status from whatsapp_templates where tenant_id = ? and name = ?",
				String.class, tenant.id, name);
	}

	private int templateCount(Tenant tenant) {
		return jdbc.queryForObject("select count(*) from whatsapp_templates where tenant_id = ?",
				Integer.class, tenant.id);
	}

	private record Tenant(UUID id, String apiKey, String wabaId) {

		String bearer() {
			return "Bearer " + apiKey;
		}
	}

	private Tenant newTenant() throws Exception {
		String slug = "wa-" + SLUGS.incrementAndGet();
		MvcResult created = mvc.perform(post("/admin/v1/tenants")
				.header("Authorization", ADMIN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"slug":"%s","name":"Acme","email":{"dailyLimit":90,"recipientCooldownSeconds":0}}
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
		return new Tenant(tenantId,
				json.readTree(issued.getResponse().getContentAsString()).get("apiKey").asText(),
				"waba-" + slug);
	}

	private Tenant onboarded() throws Exception {
		return onboarded(APP_SECRET);
	}

	private Tenant onboarded(String appSecret) throws Exception {
		stubPhoneNumber();
		stubTemplates();
		Tenant tenant = newTenant();
		register(tenant, tenant.wabaId, appSecret).andExpect(status().isCreated());
		return tenant;
	}

	private org.springframework.test.web.servlet.ResultActions register(Tenant tenant) throws Exception {
		return register(tenant, tenant.wabaId, APP_SECRET);
	}

	private org.springframework.test.web.servlet.ResultActions register(Tenant tenant, String wabaId)
			throws Exception {
		return register(tenant, wabaId, APP_SECRET);
	}

	private org.springframework.test.web.servlet.ResultActions register(Tenant tenant, String wabaId,
			String appSecret) throws Exception {
		return mvc.perform(post("/admin/v1/tenants/" + tenant.id + "/whatsapp")
				.header("Authorization", ADMIN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"phoneNumberId":"15550001111","wabaId":"%s","accessToken":"%s","appSecret":"%s",
						 "dailyLimit":500,"recipientCooldownSeconds":0}
						""".formatted(digits(wabaId), TOKEN, appSecret)));
	}

	/** The admin API takes the numeric ids Meta issues; tests name them by slug
	 * for legibility, so map that to a stable number. */
	private static String digits(String wabaId) {
		return String.valueOf(Math.abs(wabaId.hashCode()));
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder send(Tenant tenant,
			String body) {
		return post("/v1/whatsapp-messages")
				.header("Authorization", tenant.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body);
	}

	private UUID accept(Tenant tenant, String body) throws Exception {
		MvcResult result = mvc.perform(send(tenant, body)).andExpect(status().isAccepted()).andReturn();
		return UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("id").asText());
	}

	private static void stubPhoneNumber() {
		META.stubFor(WireMock.get(urlPathMatching(".*/15550001111"))
				.willReturn(okJson("{\"id\":\"15550001111\",\"display_phone_number\":\"+1 555 000 1111\"}")));
	}

	private static void stubTemplates() {
		stubTemplates("""
				{"data":[
				  {"id":"t1","name":"order_update","language":"pt_BR","category":"UTILITY","status":"APPROVED",
				   "rejected_reason":"NONE",
				   "components":[{"type":"BODY","text":"Olá {{1}}, pedido {{2}} enviado."}]},
				  {"id":"t2","name":"promo","language":"pt_BR","category":"MARKETING","status":"REJECTED",
				   "rejected_reason":"INVALID_FORMAT",
				   "components":[{"type":"BODY","text":"Aproveite!"}]}
				]}
				""");
	}

	/** WireMock prefers the most recently registered match, so calling this again
	 * mid-test is how a sweep sees a different answer than registration did. */
	private static void stubTemplates(String body) {
		META.stubFor(WireMock.get(urlPathMatching(".*/message_templates")).willReturn(okJson(body)));
	}

	private byte[] failureReceipt(String wabaId, String wamid) {
		return ("""
				{"object":"whatsapp_business_account","entry":[{"id":"%s","changes":[{"field":"messages",
				 "value":{"messaging_product":"whatsapp","statuses":[{"id":"%s","status":"failed",
				 "errors":[{"code":131026,"title":"Message undeliverable"}]}]}}]}]}
				""").formatted(digits(wabaId), wamid).getBytes(StandardCharsets.UTF_8);
	}

	private static String sign(byte[] body, String secret) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}
}
