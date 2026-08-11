package io.github.ramossvitor.herald.tenant.admin;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.ramossvitor.herald.common.ConflictException;
import io.github.ramossvitor.herald.common.NotFoundException;
import io.github.ramossvitor.herald.security.ApiKeys;
import io.github.ramossvitor.herald.tenant.ApiKey;
import io.github.ramossvitor.herald.tenant.ApiKeyRepository;
import io.github.ramossvitor.herald.tenant.Tenant;
import io.github.ramossvitor.herald.tenant.TenantEmailSettings;
import io.github.ramossvitor.herald.tenant.TenantEmailSettingsRepository;
import io.github.ramossvitor.herald.tenant.TenantLimitPolicy;
import io.github.ramossvitor.herald.tenant.TenantLimitPolicyRepository;
import io.github.ramossvitor.herald.tenant.TenantRepository;

@Service
public class TenantAdminService {

	private final TenantRepository tenants;
	private final TenantEmailSettingsRepository emailSettings;
	private final TenantLimitPolicyRepository limitPolicies;
	private final ApiKeyRepository apiKeys;
	private final Clock clock;

	public TenantAdminService(TenantRepository tenants, TenantEmailSettingsRepository emailSettings,
			TenantLimitPolicyRepository limitPolicies, ApiKeyRepository apiKeys, Clock clock) {
		this.tenants = tenants;
		this.emailSettings = emailSettings;
		this.limitPolicies = limitPolicies;
		this.apiKeys = apiKeys;
		this.clock = clock;
	}

	/**
	 * A tenant is never created without its email settings: the submission path
	 * treats missing settings as a server bug, not a recoverable state.
	 */
	@Transactional
	public Tenant createTenant(String slug, String name, String fromAddress, int dailyLimit,
			int recipientCooldownSeconds) {
		if (tenants.existsBySlug(slug)) {
			throw new ConflictException("tenant slug already exists: " + slug);
		}
		Tenant tenant = tenants.save(new Tenant(slug, name, clock.instant()));
		emailSettings.save(new TenantEmailSettings(tenant.getId(), fromAddress, dailyLimit, recipientCooldownSeconds));
		return tenant;
	}

	@Transactional(readOnly = true)
	public List<Tenant> listTenants() {
		return tenants.findAll();
	}

	@Transactional
	public void updateEmailSettings(UUID tenantId, String fromAddress, int dailyLimit, int recipientCooldownSeconds) {
		TenantEmailSettings settings = emailSettings.findById(tenantId)
				.orElseThrow(() -> new NotFoundException("tenant not found: " + tenantId));
		settings.update(fromAddress, dailyLimit, recipientCooldownSeconds);
	}

	@Transactional
	public List<TenantLimitPolicy> replaceLimitPolicies(UUID tenantId, List<PolicyInput> policies) {
		requireTenant(tenantId);
		limitPolicies.deleteByTenantId(tenantId);
		return limitPolicies.saveAll(policies.stream()
				.map(policy -> new TenantLimitPolicy(tenantId, policy.keyPrefix(), policy.dailyCap()))
				.toList());
	}

	public record PolicyInput(String keyPrefix, int dailyCap) {
	}

	@Transactional
	public IssuedKey issueApiKey(UUID tenantId, String label) {
		Tenant tenant = requireTenant(tenantId);
		ApiKeys.Generated generated = ApiKeys.generate();
		ApiKey key = apiKeys.save(
				new ApiKey(tenant, generated.hash(), generated.displayPrefix(), label, clock.instant()));
		return new IssuedKey(key.getId(), generated.plaintext(), generated.displayPrefix());
	}

	/** The plaintext lives in this record and in the HTTP response — nowhere else. */
	public record IssuedKey(UUID id, String apiKey, String keyPrefix) {
	}

	@Transactional
	public void revokeApiKey(UUID keyId) {
		ApiKey key = apiKeys.findById(keyId)
				.orElseThrow(() -> new NotFoundException("api key not found: " + keyId));
		if (key.getRevokedAt() == null) {
			key.revoke(clock.instant());
		}
	}

	private Tenant requireTenant(UUID tenantId) {
		return tenants.findById(tenantId)
				.orElseThrow(() -> new NotFoundException("tenant not found: " + tenantId));
	}
}
