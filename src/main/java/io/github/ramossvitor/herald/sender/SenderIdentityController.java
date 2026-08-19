package io.github.ramossvitor.herald.sender;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.github.ramossvitor.herald.common.NotFoundException;
import io.github.ramossvitor.herald.security.TenantPrincipal;
import jakarta.validation.Valid;

/**
 * Self-service sender identities: a tenant registers a domain it owns, gets
 * the DNS records back, publishes them, and asks for verification.
 */
@RestController
@RequestMapping("/v1/sender-identities")
public class SenderIdentityController {

	private final SenderIdentityService service;
	private final SenderIdentityRepository identities;

	public SenderIdentityController(SenderIdentityService service, SenderIdentityRepository identities) {
		this.service = service;
		this.identities = identities;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public SenderIdentityResponse register(@Valid @RequestBody RegisterDomainRequest request,
			@AuthenticationPrincipal TenantPrincipal principal) {
		return SenderIdentityResponse.from(service.registerCustomDomain(principal.tenantId(), request.domain(), false));
	}

	@GetMapping
	public List<SenderIdentityResponse> list(@AuthenticationPrincipal TenantPrincipal principal) {
		return identities.findByTenantIdAndChannelOrderByCreatedAt(principal.tenantId(), Channel.EMAIL)
				.stream().map(SenderIdentityResponse::from).toList();
	}

	@PostMapping("/{id}/verify")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public SenderIdentityResponse verify(@PathVariable UUID id, @AuthenticationPrincipal TenantPrincipal principal) {
		return SenderIdentityResponse.from(service.requestVerification(require(id, principal.tenantId())));
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable UUID id, @AuthenticationPrincipal TenantPrincipal principal) {
		service.delete(require(id, principal.tenantId()));
	}

	private SenderIdentity require(UUID id, UUID tenantId) {
		return identities.findByIdAndTenantId(id, tenantId)
				.orElseThrow(() -> new NotFoundException("sender identity not found: " + id));
	}
}
