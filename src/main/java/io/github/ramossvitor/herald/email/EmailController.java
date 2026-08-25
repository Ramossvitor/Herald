package io.github.ramossvitor.herald.email;

import java.time.Instant;
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
import io.github.ramossvitor.herald.outbox.DeliveryState;
import io.github.ramossvitor.herald.outbox.Message;
import io.github.ramossvitor.herald.outbox.MessageRepository;
import io.github.ramossvitor.herald.outbox.MessageStatus;
import io.github.ramossvitor.herald.outbox.OutboxWorker;
import io.github.ramossvitor.herald.security.TenantPrincipal;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/v1/emails")
public class EmailController {

	private final EmailSubmissionService submissions;
	private final MessageRepository messages;
	private final OutboxWorker worker;

	public EmailController(EmailSubmissionService submissions, MessageRepository messages, OutboxWorker worker) {
		this.submissions = submissions;
		this.messages = messages;
		this.worker = worker;
	}

	public record SendEmailResponse(UUID id, MessageStatus status, boolean deduplicated, Instant createdAt) {
	}

	@PostMapping
	public ResponseEntity<SendEmailResponse> send(@Valid @RequestBody SendEmailRequest request,
			@AuthenticationPrincipal TenantPrincipal principal) {
		EmailSubmissionService.Submission submission = submissions.submit(principal.tenantId(), request);
		// After the commit, so the worker's next pass can already see the row.
		worker.nudge();
		Message message = submission.message();
		return ResponseEntity.accepted().body(new SendEmailResponse(
				message.getId(), message.getStatus(), submission.deduplicated(), message.getCreatedAt()));
	}

	/**
	 * {@code status} is how far Herald got; {@code deliveryState} is what the
	 * provider reported afterwards. They answer different questions — SENT means
	 * handed over, DELIVERED means it arrived — and a SENT message with a
	 * BOUNCED delivery state is the normal way a bad address looks.
	 *
	 * {@code deliveryState} stays null until a webhook arrives, and forever if
	 * none is configured.
	 */
	public record EmailStatusResponse(UUID id, MessageStatus status, int attemptCount, String providerMessageId,
			String lastError, Instant createdAt, Instant sentAt, DeliveryState deliveryState, String deliveryDetail,
			Instant deliveryUpdatedAt) {
	}

	@GetMapping("/{id}")
	public EmailStatusResponse status(@PathVariable UUID id, @AuthenticationPrincipal TenantPrincipal principal) {
		Message message = messages.findByIdAndTenantId(id, principal.tenantId())
				.orElseThrow(() -> new NotFoundException("email not found: " + id));
		return new EmailStatusResponse(message.getId(), message.getStatus(), message.getAttemptCount(),
				message.getProviderMessageId(), message.getLastError(), message.getCreatedAt(), message.getSentAt(),
				message.getDeliveryState(), message.getDeliveryDetail(), message.getDeliveryUpdatedAt());
	}
}
