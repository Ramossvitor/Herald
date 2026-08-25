package io.github.ramossvitor.herald.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http, ApiKeyAuthenticationFilter apiKeyFilter)
			throws Exception {
		http
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						// Health is the uptime-ping target; the OpenAPI UI documents a
						// keyed API, it doesn't grant access to it.
						.requestMatchers("/actuator/health", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
						.permitAll()
						.requestMatchers("/actuator/**").hasRole("ADMIN")
						.requestMatchers("/admin/v1/**").hasRole("ADMIN")
						.anyRequest().hasRole("TENANT"))
				.exceptionHandling(handling -> handling
						// Responses written directly: an ERROR dispatch to /error would
						// be re-authorized and hide the real status.
						.authenticationEntryPoint((request, response, ex) -> {
							response.setHeader("WWW-Authenticate", "Bearer");
							response.setStatus(401);
						})
						.accessDeniedHandler((request, response, ex) -> response.setStatus(403)))
				.addFilterBefore(apiKeyFilter, AnonymousAuthenticationFilter.class);
		return http.build();
	}
}
