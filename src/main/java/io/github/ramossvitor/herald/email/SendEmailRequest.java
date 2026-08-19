package io.github.ramossvitor.herald.email;

import java.util.List;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SendEmailRequest(
		@NotBlank @Email @Size(max = 320) String to,
		@NotBlank @Size(max = 998) String subject,
		@NotBlank @Size(max = 500_000) String html,
		@NotBlank @Size(max = 100_000) String text,
		/** Optional override; must resolve to a verified identity of the tenant.
		 * Not @Email: "Acme <mail@acme.example>" is a legitimate value. */
		@Size(max = 320) String from,
		@Email @Size(max = 320) String replyTo,
		@Size(min = 1, max = 200) String idempotencyKey,
		@Size(max = 10) List<@NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]*:.{1,150}") String> limitKeys) {

	public List<String> limitKeysOrEmpty() {
		return limitKeys == null ? List.of() : limitKeys;
	}
}
