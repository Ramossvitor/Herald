package io.github.ramossvitor.herald.sender;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterDomainRequest(
		@NotBlank @Size(max = 253) @Pattern(
				regexp = "(?i)[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+") String domain) {
}
