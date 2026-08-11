package io.github.ramossvitor.herald.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

@Configuration
public class OpenApiConfig {

	@Bean
	public OpenAPI heraldOpenApi() {
		return new OpenAPI()
				.info(new Info()
						.title("Herald")
						.version("v1")
						.description("Multi-tenant transactional notification service. "
								+ "Authenticate with a tenant API key as a bearer token; "
								+ "admin endpoints require the service master key."))
				.components(new Components().addSecuritySchemes("apiKey",
						new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer")))
				.addSecurityItem(new SecurityRequirement().addList("apiKey"));
	}
}
