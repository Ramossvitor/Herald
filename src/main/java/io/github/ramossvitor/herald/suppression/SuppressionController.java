package io.github.ramossvitor.herald.suppression;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.github.ramossvitor.herald.security.TenantPrincipal;

/**
 * Addresses Herald refuses to mail for this tenant, and the way back.
 *
 * The delete endpoint is not a convenience: suppressions are added
 * automatically by the webhook path, so without a way to lift one an address
 * suppressed by a misconfigured mail server on the recipient's side would stay
 * unreachable forever, with nothing in the tenant API able to undo it.
 */
@RestController
@RequestMapping("/v1/suppressions")
public class SuppressionController {

	private final SuppressionService service;

	public SuppressionController(SuppressionService service) {
		this.service = service;
	}

	@GetMapping
	public List<SuppressionResponse> list(@AuthenticationPrincipal TenantPrincipal principal) {
		return service.list(principal.tenantId()).stream().map(SuppressionResponse::from).toList();
	}

	@DeleteMapping("/{recipient}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String recipient, @AuthenticationPrincipal TenantPrincipal principal) {
		service.remove(principal.tenantId(), recipient);
	}
}
