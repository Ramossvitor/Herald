package io.github.ramossvitor.herald.whatsapp.meta;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse;

import io.github.ramossvitor.herald.common.HeraldProperties;
import io.github.ramossvitor.herald.outbox.ChannelProvider;
import io.github.ramossvitor.herald.outbox.Classification;
import io.github.ramossvitor.herald.outbox.Message;
import io.github.ramossvitor.herald.security.Secrets;
import io.github.ramossvitor.herald.sender.Channel;
import io.github.ramossvitor.herald.whatsapp.TenantWhatsAppSettings;
import io.github.ramossvitor.herald.whatsapp.TenantWhatsAppSettingsRepository;
import io.github.ramossvitor.herald.whatsapp.WhatsAppPayload;
import io.github.ramossvitor.herald.whatsapp.WhatsAppSettingsStatus;

/**
 * Dispatch against Meta's Cloud API, under each tenant's own credentials.
 *
 * Unlike the email provider there is no operator-wide key here: the token is
 * read from the tenant's settings and decrypted per send. Herald never holds a
 * WhatsApp identity of its own.
 */
@Component
public class WhatsAppClient implements ChannelProvider {

	private static final Logger log = LoggerFactory.getLogger(WhatsAppClient.class);

	private static final int MAX_ERROR_LENGTH = 500;

	private final HeraldProperties.WhatsApp properties;
	private final TenantWhatsAppSettingsRepository settings;
	private final Secrets secrets;
	private final Clock clock;
	private final RestClient rest;

	public WhatsAppClient(HeraldProperties properties, TenantWhatsAppSettingsRepository settings, Secrets secrets,
			Clock clock) {
		this.properties = properties.whatsapp();
		this.settings = settings;
		this.secrets = secrets;
		this.clock = clock;
		HttpClient httpClient = HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(this.properties.connectTimeout())
				.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(this.properties.readTimeout());
		this.rest = RestClient.builder()
				.baseUrl(this.properties.baseUrl())
				.requestFactory(requestFactory)
				.build();
	}

	@Override
	public Channel channel() {
		return Channel.WHATSAPP;
	}

	/**
	 * Without the encryption key no tenant credential can be read back, so the
	 * channel has nothing to send with. Messages queue rather than fail.
	 */
	@Override
	public boolean configured() {
		return secrets.available();
	}

	@Override
	public Duration sendInterval() {
		return properties.sendInterval();
	}

	/** {@code httpStatus == null} means the request never completed. */
	public record Outcome(Integer httpStatus, String body, Integer retryAfterSeconds, String transportError) {

		public boolean transportFailed() {
			return httpStatus == null;
		}
	}

	@Override
	public Attempt send(Message message) {
		TenantWhatsAppSettings tenantSettings = settings.findById(message.getTenantId()).orElse(null);
		if (tenantSettings == null) {
			// Submission checks for this, so reaching it means the credentials
			// were removed while the message sat in the queue. Nothing to retry.
			return Attempt.failed(Classification.REJECTED, "tenant has no WhatsApp credentials", null);
		}

		String token;
		try {
			token = secrets.decrypt(tenantSettings.getAccessToken(),
					TenantWhatsAppSettings.accessTokenContext(message.getTenantId()));
		}
		catch (RuntimeException ex) {
			// UNAVAILABLE, not REJECTED: much the likeliest cause is a wrong
			// HERALD_SECRET_KEY — rotated, restored from the wrong backup, a
			// truncated env var — and configured() cannot catch that, because it
			// only knows whether a key is present. Failing terminally here would
			// walk the whole backlog at sendInterval and destroy it for a
			// misconfiguration that is fixed by correcting one variable.
			// maxAttempts still bounds the genuinely tampered row.
			return Attempt.failed(Classification.UNAVAILABLE, "stored credentials could not be read", null);
		}

		// The number is the snapshot taken at submission, so editing settings
		// does not redirect a queued message; the token is read fresh, because a
		// rotated one is the only one that still works.
		Outcome outcome = post(token, message.getSender() + "/messages", body(message));

		if (outcome.transportFailed()) {
			return Attempt.failed(Classification.UNAVAILABLE, truncate("transport: " + outcome.transportError()), null);
		}
		Classification classification = WhatsAppResponseClassifier.classify(outcome.httpStatus(), outcome.body());
		if (classification == Classification.SUCCESS) {
			return Attempt.success(WhatsAppResponseClassifier.providerMessageId(outcome.body()));
		}
		String reason = truncate(WhatsAppResponseClassifier.describe(outcome.httpStatus(), outcome.body()));
		if (WhatsAppResponseClassifier.isAuthFailure(outcome.httpStatus(), outcome.body())) {
			// The queued messages back off, but submission must stop accepting new
			// ones under a token that no longer works — otherwise the only sign an
			// operator gets is one log line per message.
			markCredentialsFailed(tenantSettings, reason);
		}
		return Attempt.failed(classification, reason, outcome.retryAfterSeconds());
	}

	private static Map<String, Object> body(Message message) {
		Map<String, Object> template = new LinkedHashMap<>();
		template.put("name", WhatsAppPayload.template(message));
		template.put("language", Map.of("code", WhatsAppPayload.language(message)));

		List<String> params = WhatsAppPayload.params(message);
		if (!params.isEmpty()) {
			List<Map<String, Object>> parameters = new ArrayList<>(params.size());
			for (String param : params) {
				parameters.add(Map.of("type", "text", "text", param));
			}
			template.put("components", List.of(Map.of("type", "body", "parameters", parameters)));
		}

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("messaging_product", "whatsapp");
		body.put("recipient_type", "individual");
		body.put("to", message.getRecipientCanonical());
		body.put("type", "template");
		body.put("template", template);
		return body;
	}

	/** Reads the templates Meta holds for a WhatsApp Business Account. */
	public Outcome listTemplates(String token, String wabaId, int limit) {
		try {
			return rest.get()
					.uri(uriBuilder -> uriBuilder
							.path("/" + properties.apiVersion() + "/" + wabaId + "/message_templates")
							.queryParam("fields", "name,language,category,status,components,rejected_reason,id")
							.queryParam("limit", limit)
							.build())
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
					.exchange(WhatsAppClient::toOutcome);
		}
		catch (Exception ex) {
			return new Outcome(null, null, null, ex.getMessage());
		}
	}

	/**
	 * Reads the sending number back. Used to prove credentials before a tenant
	 * is allowed to depend on them — the cheapest call that exercises both the
	 * token and the phone number id together.
	 */
	public Outcome getPhoneNumber(String token, String phoneNumberId) {
		try {
			return rest.get()
					.uri("/" + properties.apiVersion() + "/" + phoneNumberId + "?fields=display_phone_number,verified_name")
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
					.exchange(WhatsAppClient::toOutcome);
		}
		catch (Exception ex) {
			return new Outcome(null, null, null, ex.getMessage());
		}
	}

	private Outcome post(String token, String path, Map<String, Object> body) {
		try {
			return rest.post()
					.uri("/" + properties.apiVersion() + "/" + path)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
					.contentType(MediaType.APPLICATION_JSON)
					.body(body)
					.exchange(WhatsAppClient::toOutcome);
		}
		catch (Exception ex) {
			return new Outcome(null, null, null, ex.getMessage());
		}
	}

	/**
	 * Best effort: the send has already been classified and the outcome is what
	 * the worker acts on, so a failure to record the credential state must not
	 * turn into a second, different failure for the message.
	 */
	private void markCredentialsFailed(TenantWhatsAppSettings tenantSettings, String reason) {
		if (tenantSettings.getStatus() == WhatsAppSettingsStatus.FAILED) {
			return;
		}
		try {
			tenantSettings.markFailed(reason, clock.instant());
			settings.save(tenantSettings);
			log.warn("WhatsApp credentials for tenant {} rejected by Meta; marked FAILED, re-verify to resume",
					tenantSettings.getTenantId());
		}
		catch (RuntimeException ex) {
			log.warn("could not record failed WhatsApp credentials for tenant {}", tenantSettings.getTenantId(), ex);
		}
	}

	private static String truncate(String value) {
		return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
	}

	private static Outcome toOutcome(HttpRequest request, ConvertibleClientHttpResponse response) throws IOException {
		String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
		return new Outcome(response.getStatusCode().value(), body,
				parseRetryAfter(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)), null);
	}

	private static Integer parseRetryAfter(String header) {
		if (header == null) {
			return null;
		}
		try {
			int seconds = Integer.parseInt(header.trim());
			return seconds >= 0 ? seconds : null;
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}
}
