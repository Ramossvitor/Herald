package io.github.ramossvitor.herald.whatsapp;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Where a tenant's own WhatsApp account is handed over. Admin-only: the request
 * carries a token and an app secret, which are the tenant's crown jewels on that
 * channel, and no response ever gives them back.
 */
@RestController
@RequestMapping("/admin/v1/tenants/{tenantId}/whatsapp")
public class AdminWhatsAppController {

	private final WhatsAppAdminService service;

	public AdminWhatsAppController(WhatsAppAdminService service) {
		this.service = service;
	}

	/**
	 * @param accessToken a permanent system user token from the tenant's own
	 *        Meta app, carrying whatsapp_business_messaging and
	 *        whatsapp_business_management
	 * @param appSecret that app's secret, used to verify the HMAC on inbound
	 *        webhooks — without it delivery receipts cannot be trusted
	 */
	public record RegisterRequest(
			@NotBlank @Size(max = 64) @Pattern(regexp = "[0-9]+",
					message = "must be the numeric phone number id from Meta") String phoneNumberId,
			@NotBlank @Size(max = 64) @Pattern(regexp = "[0-9]+",
					message = "must be the numeric WhatsApp Business Account id") String wabaId,
			@NotBlank @Size(max = 1024) String accessToken,
			@NotBlank @Size(max = 256) String appSecret,
			// Boxed and @NotNull on purpose: an omitted primitive would arrive as
			// zero, pass @Min, and store a tenant whose every send is refused by
			// the quota check — a 201 on a channel that can never send.
			@NotNull @Min(1) @Max(1_000_000) Integer dailyLimit,
			@NotNull @Min(0) @Max(86_400) Integer recipientCooldownSeconds) {
	}

	/** Never carries the credentials back — only what they point at. */
	public record WhatsAppSettingsResponse(String phoneNumberId, String wabaId, String status, String lastError,
			int dailyLimit, int recipientCooldownSeconds, Instant templatesSyncedAt) {

		static WhatsAppSettingsResponse from(TenantWhatsAppSettings settings) {
			return new WhatsAppSettingsResponse(settings.getPhoneNumberId(), settings.getWabaId(),
					settings.getStatus().name(), settings.getLastError(), settings.getDailyLimit(),
					settings.getRecipientCooldownSeconds(), settings.getTemplatesSyncedAt());
		}
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public WhatsAppSettingsResponse register(@PathVariable UUID tenantId, @Valid @RequestBody RegisterRequest request) {
		return WhatsAppSettingsResponse.from(service.register(tenantId, new WhatsAppAdminService.Credentials(
				request.phoneNumberId(), request.wabaId(), request.accessToken(), request.appSecret(),
				request.dailyLimit(), request.recipientCooldownSeconds())));
	}

	@GetMapping
	public WhatsAppSettingsResponse get(@PathVariable UUID tenantId) {
		return WhatsAppSettingsResponse.from(service.get(tenantId));
	}

	/** Re-proves stored credentials — after a token rotation, or a Meta outage
	 * that left them PENDING. */
	@PostMapping("/verify")
	public WhatsAppSettingsResponse verify(@PathVariable UUID tenantId) {
		return WhatsAppSettingsResponse.from(service.verify(tenantId));
	}

	/** Both required: a PUT that omitted one would silently zero it. */
	public record LimitsInput(
			@NotNull @Min(1) @Max(1_000_000) Integer dailyLimit,
			@NotNull @Min(0) @Max(86_400) Integer recipientCooldownSeconds) {
	}

	@PutMapping("/limits")
	public WhatsAppSettingsResponse updateLimits(@PathVariable UUID tenantId, @Valid @RequestBody LimitsInput request) {
		return WhatsAppSettingsResponse.from(
				service.updateLimits(tenantId, request.dailyLimit(), request.recipientCooldownSeconds()));
	}

	@DeleteMapping
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable UUID tenantId) {
		service.delete(tenantId);
	}
}
