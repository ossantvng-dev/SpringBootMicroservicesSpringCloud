package com.photoapp.users.configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/*
    The bearerAuth scheme mirrors what JwtFilter expects on the wire: the raw
    access token from POST /auth/login, sent as "Authorization: Bearer <token>".
    Swagger UI's Authorize button therefore produces a request the service
    actually accepts, without any extra wiring.
 */
@Configuration
public class OpenApiConfiguration {

    @Bean
    public OpenAPI usersServiceOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Photo App - Users Service")
                        .version("v1")
                        .description("""
                                User registration, lookup, activation and role assignment.
                                Registration (POST /users) and username lookup are public;
                                everything else requires a JWT with ROLE_USER or ROLE_ADMIN."""))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }

}
