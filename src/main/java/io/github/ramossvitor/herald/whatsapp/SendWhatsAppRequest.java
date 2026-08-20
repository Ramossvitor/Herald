package io.github.ramossvitor.herald.whatsapp;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Unlike an email, the words are not here. Meta approved them ahead of time and
 * the caller names the template and fills its blanks — so this request carries
 * a reference and arguments, never a body.
 *
 * @param to E.164, with the plus. Herald will not infer a country code
 *        ({@link PhoneNumbers}).
 * @param params body placeholders in order; the count must match what Meta
 *        approved
 */
public record SendWhatsAppRequest(
		@NotBlank @Size(max = 32) @Pattern(regexp = "\\+[0-9 ().-]{7,31}",
				message = "must be an E.164 number starting with +") String to,
		// Meta's own naming rule for templates.
		@NotBlank @Size(max = 512) @Pattern(regexp = "[a-z0-9_]+",
				message = "must be lowercase letters, digits and underscores") String template,
		@NotBlank @Size(max = 12) @Pattern(regexp = "[a-zA-Z]{2,3}(_[a-zA-Z]{2,4})?",
				message = "must be a language code like pt_BR or en") String language,
		@Size(max = 20) List<@NotNull @Size(max = 1024) String> params,
		@Size(min = 1, max = 200) String idempotencyKey,
		@Size(max = 10) List<@NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]*:.{1,150}") String> limitKeys) {

	public List<String> paramsOrEmpty() {
		return params == null ? List.of() : params;
	}

	public List<String> limitKeysOrEmpty() {
		return limitKeys == null ? List.of() : limitKeys;
	}
}
