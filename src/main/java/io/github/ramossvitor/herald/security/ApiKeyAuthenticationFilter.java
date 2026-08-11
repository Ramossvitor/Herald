package io.github.ramossvitor.herald.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import io.github.ramossvitor.herald.common.HeraldProperties;
import io.github.ramossvitor.herald.tenant.ApiKey;
import io.github.ramossvitor.herald.tenant.ApiKeyRepository;
import io.github.ramossvitor.herald.tenant.TenantStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Resolves the bearer token into either the service admin or a tenant. An
 * unresolvable token simply leaves the request unauthenticated — the
 * authorization rules then answer 401. The one case decided here is a
 * suspended tenant: its key is valid, so it gets an explicit 403.
 */
@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

	private static final Duration LAST_USED_WRITE_INTERVAL = Duration.ofHours(1);

	private final ApiKeyRepository apiKeys;
	private final Clock clock;

	/** Null when no admin key is configured: the admin surface stays closed. */
	private final String adminKeyHash;

	public ApiKeyAuthenticationFilter(ApiKeyRepository apiKeys, HeraldProperties properties, Clock clock) {
		this.apiKeys = apiKeys;
		this.clock = clock;
		this.adminKeyHash = properties.adminApiKey().isBlank() ? null : ApiKeys.sha256Hex(properties.adminApiKey());
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String token = bearerToken(request);
		if (token == null) {
			chain.doFilter(request, response);
			return;
		}

		String tokenHash = ApiKeys.sha256Hex(token);

		if (adminKeyHash != null && ApiKeys.hashesMatch(tokenHash, adminKeyHash)) {
			authenticate("admin", "ROLE_ADMIN");
			try {
				chain.doFilter(request, response);
			}
			finally {
				MDC.remove("tenant");
			}
			return;
		}

		ApiKey key = token.startsWith(ApiKeys.LIVE_PREFIX)
				? apiKeys.findActiveByKeyHash(tokenHash).orElse(null)
				: null;
		if (key == null) {
			chain.doFilter(request, response);
			return;
		}
		if (key.getTenant().getStatus() == TenantStatus.SUSPENDED) {
			reject(response, HttpServletResponse.SC_FORBIDDEN, "Tenant suspended");
			return;
		}

		touchThrottled(key);
		TenantPrincipal principal = new TenantPrincipal(key.getTenant().getId(), key.getTenant().getSlug());
		authenticate(principal, "ROLE_TENANT");
		MDC.put("tenant", principal.slug());
		try {
			chain.doFilter(request, response);
		}
		finally {
			MDC.remove("tenant");
		}
	}

	private static String bearerToken(HttpServletRequest request) {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header == null || !header.startsWith("Bearer ")) {
			return null;
		}
		String token = header.substring("Bearer ".length()).trim();
		return token.isEmpty() ? null : token;
	}

	private static void authenticate(Object principal, String role) {
		var authentication = UsernamePasswordAuthenticationToken.authenticated(
				principal, null, List.of(new SimpleGrantedAuthority(role)));
		SecurityContextHolder.getContext().setAuthentication(authentication);
	}

	private void touchThrottled(ApiKey key) {
		Instant now = clock.instant();
		Instant lastUsed = key.getLastUsedAt();
		if (lastUsed == null || lastUsed.isBefore(now.minus(LAST_USED_WRITE_INTERVAL))) {
			apiKeys.markUsed(key.getId(), now);
		}
	}

	private static void reject(HttpServletResponse response, int status, String title) throws IOException {
		// Written directly instead of sendError: an ERROR dispatch would run the
		// authorization rules again and turn this into a misleading 401.
		response.setStatus(status);
		response.setContentType("application/problem+json");
		response.setCharacterEncoding(StandardCharsets.UTF_8);
		response.getWriter().write("{\"status\":" + status + ",\"title\":\"" + title + "\"}");
		response.getWriter().flush();
	}
}
