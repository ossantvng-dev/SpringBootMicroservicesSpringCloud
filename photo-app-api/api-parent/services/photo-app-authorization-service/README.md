# photo-app-authorization-service

Issues, refreshes and revokes JWTs. The entry point for authentication — nothing else in the
system can be used without a token from here.

**Port:** 8085 (not published — reached through the gateway)

## Swagger UI

- Through the gateway: http://localhost:8080/swagger-ui.html → select **authorization-service**
- Direct (native runs only): http://localhost:8085/swagger-ui.html

## Endpoints

All three are **public** — this is what issues the token, so it cannot require one.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/auth/login` | Username + password → access token + refresh token |
| `POST` | `/auth/refresh` | Refresh token → new access token |
| `POST` | `/auth/revoke` | Invalidate a refresh token |

Start here, then paste the returned `accessToken` into the **Authorize** dialog of any other
service's Swagger UI.

## How authentication works

`CustomUserDetailsService` loads the user and BCrypt-verifies the password;
`JwtTokenProvider` signs a JWT with the shared HMAC secret. Roles travel in the `scope` claim.
Every other service then validates that token **locally** — there is no introspection call back
here, which is why this service is not on the hot path of ordinary requests.

## The PKCE registration is not a PKCE flow

`AuthorizationServerConfig` registers an in-memory `RegisteredClient` (`photoapp-client`,
`AUTHORIZATION_CODE`, `ClientAuthenticationMethod.NONE`) that is *shaped* like a PKCE public
client. But the real login path is the custom `POST /auth/login` endpoint above — no
`code_challenge` is ever issued or verified.

This is a recorded simplification, not an oversight, and the plan is **not** to finish building
it: the platform decision is to adopt **AWS Cognito** and become a verify-only resource server
against its JWKS endpoint. See [`../../../docs/plans/backlog.txt`](../../../docs/plans/backlog.txt).

## Depends on

| On | Why |
|---|---|
| MySQL | User credentials, refresh tokens |
| Config Server, Discovery, RabbitMQ, Zipkin | Standard for every service |
| `photo-app-entity-model-lib` | JPA entities |
| `photo-app-security-lib` | JWT provider and parser |
| `photo-app-feign-lib` | Declared; not on a hot path |

Notably it does **not** depend on `photo-app-commons` — it owns its own DTOs.

Calls no other business service.

## Environment variables

| Variable | Required | Purpose |
|---|---|---|
| `CONFIG_SERVER_ADMIN_USER` / `CONFIG_SERVER_ADMIN_PASSWORD` | yes | Config Server auth |
| `CONFIG_SERVER_URL` | container | `http://photo-app-config-server:8888` |
| `SPRING_DATASOURCE_URL` | container | Points at `photo-app-mysql` |
| `EUREKA_CLIENT_SERVICEURL_DEFAULTZONE`, `EUREKA_INSTANCE_HOSTNAME` | container | Registration |
| `SPRING_RABBITMQ_HOST`, `MANAGEMENT_ZIPKIN_TRACING_ENDPOINT` | container | Bus and tracing |

Key config-repo properties: `photoapp.jwt.secret`, `photoapp.jwt.validity`.

## Quick check

```bash
curl -X POST http://localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"generic"}'
```

Seed credentials are in [`../../../docs/notes/users.txt`](../../../docs/notes/users.txt) — local
dev data only.

## See also

- [../../../docs/ARCHITECTURE.md](../../../docs/ARCHITECTURE.md) — the full token flow
