package io.github.ramossvitor.herald.whatsapp;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.ramossvitor.herald.common.NotFoundException;
import io.github.ramossvitor.herald.outbox.Message;
import io.github.ramossvitor.herald.outbox.MessageRepository;
import io.github.ramossvitor.herald.outbox.MessageStatus;
import io.github.ramossvitor.herald.outbox.OutboxWorker;
import io.github.ramossvitor.herald.security.TenantPrincipal;
import io.github.ramossvitor.herald.sender.Channel;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/v1")
public class WhatsAppController {

	private final WhatsAppSubmissionService submissions;
	private final MessageRepository messages;
	private final WhatsAppTemplateRepository templates;
	private final OutboxWorker worker;

	public WhatsAppController(WhatsAppSubmissionService submissions, MessageRepository messages,
			WhatsAppTemplateRepository templates, OutboxWorker worker) {
		this.submissions = submissions;
		this.messages = messages;
		this.templates = templates;
		this.worker = worker;
	}

	public record SendWhatsAppResponse(UUID id, MessageStatus status, boolean deduplicated, Instant createdAt) {
	}

	@PostMapping("/whatsapp-messages")
	public ResponseEntity<SendWhatsAppResponse> send(@Valid @RequestBody SendWhatsAppRequest request,
			@AuthenticationPrincipal TenantPrincipal principal) {
		WhatsAppSubmissionService.Submission submission = submissions.submit(principal.tenantId(), request);
		// After the commit, so the worker's next pass can already see the row.
		worker.nudge();
		Message message = submission.message();
		return ResponseEntity.accepted().body(new SendWhatsAppResponse(
				message.getId(), message.getStatus(), submission.deduplicated(), message.getCreatedAt()));
	}

	public record WhatsAppStatusResponse(UUID id, MessageStatus status, int attemptCount, String providerMessageId,
			String lastError, Instant createdAt, Instant sentAt) {
	}

	@GetMapping("/whatsapp-messages/{id}")
	public WhatsAppStatusResponse status(@PathVariable UUID id, @AuthenticationPrincipal TenantPrincipal principal) {
		Message message = messages.findByIdAndTenantIdAndChannel(id, principal.tenantId(), Channel.WHATSAPP)
				.orElseThrow(() -> new NotFoundException("whatsapp message not found: " + id));
		return new WhatsAppStatusResponse(message.getId(), message.getStatus(), message.getAttemptCount(),
				message.getProviderMessageId(), message.getLastError(), message.getCreatedAt(), message.getSentAt());
	}

	/**
	 * What Meta approved, as Herald last read it. Templates are still authored in
	 * the WhatsApp Manager — this is the list a caller needs to know which names,
	 * languages and argument counts a send may use, without a second console.
	 */
	public record TemplateResponse(String name, String language, String category, String status, int paramCount,
			String rejectedReason, Instant syncedAt) {
	}

	@GetMapping("/whatsapp-templates")
	public List<TemplateResponse> listTemplates(@AuthenticationPrincipal TenantPrincipal principal) {
		return templates.findByTenantIdOrderByNameAscLanguageAsc(principal.tenantId()).stream()
				.map(template -> new TemplateResponse(template.getName(), template.getLanguage(),
						template.getCategory(), template.getStatus(), template.getBodyParamCount(),
						template.getRejectedReason(), template.getSyncedAt()))
				.toList();
	}
}
