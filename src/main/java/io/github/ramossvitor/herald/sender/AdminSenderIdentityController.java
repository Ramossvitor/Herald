package io.github.ramossvitor.herald.sender;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.github.ramossvitor.herald.common.NotFoundException;
import jakarta.validation.Valid;

/**
 * The same lifecycle over any tenant. This is also how the operator provisions
 * a dedicated subdomain of their own root for a tenant that wants isolated
 * sending reputation.
 */
@RestController
@RequestMapping("/admin/v1/tenants/{tenantId}/sender-identities")
public class AdminSenderIdentityController {

	private final SenderIdentityService service;
	private final SenderIdentityRepository identities;

	public AdminSenderIdentityController(SenderIdentityService service, SenderIdentityRepository identities) {
		this.service = service;
		this.identities = identities;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public SenderIdentityResponse register(@PathVariable UUID tenantId,
			@Valid @RequestBody RegisterDomainRequest request) {
		return SenderIdentityResponse.from(service.registerCustomDomain(tenantId, request.domain()));
	}

	@GetMapping
	public List<SenderIdentityResponse> list(@PathVariable UUID tenantId) {
		return identities.findByTenantIdAndChannelOrderByCreatedAt(tenantId, Channel.EMAIL)
				.stream().map(SenderIdentityResponse::from).toList();
	}

	@PostMapping("/{id}/verify")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public SenderIdentityResponse verify(@PathVariable UUID tenantId, @PathVariable UUID id) {
		return SenderIdentityResponse.from(service.requestVerification(require(id, tenantId)));
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable UUID tenantId, @PathVariable UUID id) {
		service.delete(require(id, tenantId));
	}

	private SenderIdentity require(UUID id, UUID tenantId) {
		return identities.findByIdAndTenantId(id, tenantId)
				.orElseThrow(() -> new NotFoundException("sender identity not found: " + id));
	}
}
