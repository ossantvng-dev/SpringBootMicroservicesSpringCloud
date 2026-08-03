package com.photoapp.auth.configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/*
    No global security requirement here: every endpoint on this service is public
    by design (it is what issues the token in the first place). Start with
    POST /auth/login, then paste the returned access token into the Authorize
    dialog of any other service's Swagger UI.
 */
@Configuration
public class OpenApiConfiguration {

    @Bean
    public OpenAPI authorizationServiceOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Photo App - Authorization Service")
                        .version("v1")
                        .description("""
                                Issues, refreshes and revokes JWTs. Tokens are signed with a
                                shared HMAC secret that every service validates locally.
                                See docs/ARCHITECTURE.md for the full token flow."""));
    }

}
