package io.github.ramossvitor.herald.common;

import java.net.URI;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import io.github.ramossvitor.herald.email.QuotaExceededException;
import io.github.ramossvitor.herald.sender.SenderNotVerifiedException;

/**
 * Every error leaves the API as an RFC 9457 problem document. The {@code type}
 * URIs are relative identifiers, not links — clients switch on them.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ProblemDetail onValidationFailure(MethodArgumentNotValidException ex) {
		ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_CONTENT);
		problem.setType(URI.create("/errors/validation"));
		problem.setTitle("Validation failed");
		List<String> errors = ex.getBindingResult().getFieldErrors().stream()
				.map(error -> error.getField() + ": " + error.getDefaultMessage())
				.toList();
		problem.setProperty("errors", errors);
		return problem;
	}

	@ExceptionHandler(QuotaExceededException.class)
	public ResponseEntity<ProblemDetail> onQuotaExceeded(QuotaExceededException ex) {
		ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
		problem.setType(URI.create("/errors/quota"));
		problem.setTitle("Quota exceeded");
		problem.setProperty("reason", ex.reason().wireName());
		if (ex.limitKey() != null) {
			problem.setProperty("limitKey", ex.limitKey());
		}
		ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS);
		if (ex.retryAfterSeconds() != null) {
			problem.setProperty("retryAfterSeconds", ex.retryAfterSeconds());
			response.header("Retry-After", String.valueOf(ex.retryAfterSeconds()));
		}
		return response.body(problem);
	}

	@ExceptionHandler(SenderNotVerifiedException.class)
	public ProblemDetail onSenderNotVerified(SenderNotVerifiedException ex) {
		ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_CONTENT);
		problem.setType(URI.create("/errors/sender-not-verified"));
		problem.setTitle("Sender not verified");
		problem.setProperty("from", ex.from());
		return problem;
	}

	@ExceptionHandler(NotFoundException.class)
	public ProblemDetail onNotFound(NotFoundException ex) {
		ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
		problem.setType(URI.create("/errors/not-found"));
		problem.setTitle("Not found");
		problem.setDetail(ex.getMessage());
		return problem;
	}

	@ExceptionHandler(ConflictException.class)
	public ProblemDetail onConflict(ConflictException ex) {
		ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
		problem.setType(URI.create("/errors/conflict"));
		problem.setTitle("Conflict");
		problem.setDetail(ex.getMessage());
		return problem;
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ProblemDetail onUnreadableBody(HttpMessageNotReadableException ex) {
		ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
		problem.setType(URI.create("/errors/malformed-body"));
		problem.setTitle("Malformed request body");
		return problem;
	}
}
