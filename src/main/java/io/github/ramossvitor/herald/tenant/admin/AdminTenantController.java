package io.github.ramossvitor.herald.tenant.admin;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.github.ramossvitor.herald.tenant.Tenant;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/admin/v1")
public class AdminTenantController {

	private final TenantAdminService service;

	public AdminTenantController(TenantAdminService service) {
		this.service = service;
	}

	public record EmailSettingsInput(
			@NotBlank @Size(max = 320) String fromAddress,
			@Min(0) @Max(1_000_000) int dailyLimit,
			@Min(0) @Max(86_400) int recipientCooldownSeconds) {
	}

	public record CreateTenantRequest(
			@NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{1,49}") String slug,
			@NotBlank @Size(max = 200) String name,
			@NotNull @Valid EmailSettingsInput email) {
	}

	public record TenantResponse(UUID id, String slug, String name, String status) {
		static TenantResponse from(Tenant tenant) {
			return new TenantResponse(tenant.getId(), tenant.getSlug(), tenant.getName(),
					tenant.getStatus().name());
		}
	}

	@PostMapping("/tenants")
	@ResponseStatus(HttpStatus.CREATED)
	public TenantResponse createTenant(@Valid @RequestBody CreateTenantRequest request) {
		Tenant tenant = service.createTenant(request.slug(), request.name(), request.email().fromAddress(),
				request.email().dailyLimit(), request.email().recipientCooldownSeconds());
		return TenantResponse.from(tenant);
	}

	@GetMapping("/tenants")
	public List<TenantResponse> listTenants() {
		return service.listTenants().stream().map(TenantResponse::from).toList();
	}

	@PutMapping("/tenants/{tenantId}/email-settings")
	public void updateEmailSettings(@PathVariable UUID tenantId, @Valid @RequestBody EmailSettingsInput request) {
		service.updateEmailSettings(tenantId, request.fromAddress(), request.dailyLimit(),
				request.recipientCooldownSeconds());
	}

	public record LimitPolicyInput(
			@NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,49}") String keyPrefix,
			@Min(0) @Max(1_000_000) int dailyCap) {
	}

	@PutMapping("/tenants/{tenantId}/limit-policies")
	public List<LimitPolicyInput> replaceLimitPolicies(@PathVariable UUID tenantId,
			@RequestBody List<@Valid LimitPolicyInput> policies) {
		return service
				.replaceLimitPolicies(tenantId, policies.stream()
						.map(policy -> new TenantAdminService.PolicyInput(policy.keyPrefix(), policy.dailyCap()))
						.toList())
				.stream()
				.map(saved -> new LimitPolicyInput(saved.getKeyPrefix(), saved.getDailyCap()))
				.toList();
	}
}
