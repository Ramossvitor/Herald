package io.github.ramossvitor.herald.tenant.admin;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/admin/v1")
public class AdminApiKeyController {

	private final TenantAdminService service;

	public AdminApiKeyController(TenantAdminService service) {
		this.service = service;
	}

	public record IssueKeyRequest(@Size(max = 200) String label) {
	}

	public record IssuedKeyResponse(UUID id, String apiKey, String keyPrefix) {
	}

	@PostMapping("/tenants/{tenantId}/api-keys")
	@ResponseStatus(HttpStatus.CREATED)
	public IssuedKeyResponse issueKey(@PathVariable UUID tenantId, @Valid @RequestBody(required = false) IssueKeyRequest request) {
		TenantAdminService.IssuedKey issued = service.issueApiKey(tenantId,
				request == null ? null : request.label());
		return new IssuedKeyResponse(issued.id(), issued.apiKey(), issued.keyPrefix());
	}

	@DeleteMapping("/api-keys/{keyId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void revokeKey(@PathVariable UUID keyId) {
		service.revokeApiKey(keyId);
	}
}
