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
import io.github.ramossvitor.herald.email.outbox.OutboxWorker;
import io.github.ramossvitor.herald.security.TenantPrincipal;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/v1/emails")
public class EmailController {

	private final EmailSubmissionService submissions;
	private final EmailMessageRepository messages;
	private final OutboxWorker worker;

	public EmailController(EmailSubmissionService submissions, EmailMessageRepository messages, OutboxWorker worker) {
		this.submissions = submissions;
		this.messages = messages;
		this.worker = worker;
	}

	public record SendEmailResponse(UUID id, EmailStatus status, boolean deduplicated, Instant createdAt) {
	}

	@PostMapping
	public ResponseEntity<SendEmailResponse> send(@Valid @RequestBody SendEmailRequest request,
			@AuthenticationPrincipal TenantPrincipal principal) {
		EmailSubmissionService.Submission submission = submissions.submit(principal.tenantId(), request);
		// After the commit, so the worker's next pass can already see the row.
		worker.nudge();
		EmailMessage message = submission.message();
		return ResponseEntity.accepted().body(new SendEmailResponse(
				message.getId(), message.getStatus(), submission.deduplicated(), message.getCreatedAt()));
	}

	public record EmailStatusResponse(UUID id, EmailStatus status, int attemptCount, String providerMessageId,
			String lastError, Instant createdAt, Instant sentAt) {
	}

	@GetMapping("/{id}")
	public EmailStatusResponse status(@PathVariable UUID id, @AuthenticationPrincipal TenantPrincipal principal) {
		EmailMessage message = messages.findByIdAndTenantId(id, principal.tenantId())
				.orElseThrow(() -> new NotFoundException("email not found: " + id));
		return new EmailStatusResponse(message.getId(), message.getStatus(), message.getAttemptCount(),
				message.getProviderMessageId(), message.getLastError(), message.getCreatedAt(), message.getSentAt());
	}
}
