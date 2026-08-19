package io.github.ramossvitor.herald.email.resend;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse;

import io.github.ramossvitor.herald.common.HeraldProperties;
import io.github.ramossvitor.herald.email.EmailMessage;

@Component
public class ResendClient {

	private final HeraldProperties.Resend properties;
	private final RestClient rest;

	public ResendClient(HeraldProperties properties) {
		this.properties = properties.resend();
		HttpClient httpClient = HttpClient.newBuilder()
				// The JDK client defaults to HTTP/2 and not every server (or
				// test double) negotiates it cleanly for POSTs; one request per
				// send gains nothing from h2 anyway.
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

	/**
	 * A missing key pauses dispatch (messages stay PENDING) instead of failing
	 * them against a provider that was never called.
	 */
	public boolean configured() {
		return !properties.apiKey().isBlank();
	}

	/** {@code httpStatus == null} means the request never completed (network, timeout). */
	public record Outcome(Integer httpStatus, String body, Integer retryAfterSeconds, String transportError) {

		public boolean transportFailed() {
			return httpStatus == null;
		}
	}

	public Outcome send(EmailMessage message) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("from", message.getFromAddress());
		payload.put("to", List.of(message.getRecipient()));
		payload.put("subject", message.getSubject());
		payload.put("html", message.getHtmlBody());
		payload.put("text", message.getTextBody());
		if (message.getReplyTo() != null) {
			payload.put("reply_to", message.getReplyTo());
		}

		try {
			return rest.post()
					.uri("/emails")
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
					// The message id doubles as the provider idempotency key: a
					// retry after a crash between send and record cannot
					// deliver the same email twice.
					.header("Idempotency-Key", message.getId().toString())
					.contentType(MediaType.APPLICATION_JSON)
					.body(payload)
					.exchange(ResendClient::toOutcome);
		}
		catch (Exception ex) {
			return new Outcome(null, null, null, ex.getMessage());
		}
	}

	/**
	 * Domain registration returns the DNS records the owner has to publish;
	 * verification is the provider re-reading DNS, which is why it is a poll
	 * and not a synchronous answer.
	 */
	public Outcome createDomain(String name) {
		try {
			return rest.post()
					.uri("/domains")
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
					.contentType(MediaType.APPLICATION_JSON)
					.body(Map.of("name", name))
					.exchange(ResendClient::toOutcome);
		}
		catch (Exception ex) {
			return new Outcome(null, null, null, ex.getMessage());
		}
	}

	public Outcome getDomain(String domainId) {
		try {
			return rest.get()
					.uri("/domains/{id}", domainId)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
					.exchange(ResendClient::toOutcome);
		}
		catch (Exception ex) {
			return new Outcome(null, null, null, ex.getMessage());
		}
	}

	public Outcome verifyDomain(String domainId) {
		try {
			return rest.post()
					.uri("/domains/{id}/verify", domainId)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
					.exchange(ResendClient::toOutcome);
		}
		catch (Exception ex) {
			return new Outcome(null, null, null, ex.getMessage());
		}
	}

	public Outcome deleteDomain(String domainId) {
		try {
			return rest.delete()
					.uri("/domains/{id}", domainId)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
					.exchange(ResendClient::toOutcome);
		}
		catch (Exception ex) {
			return new Outcome(null, null, null, ex.getMessage());
		}
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
