# photo-app-security-lib

JWT authentication and the **single** security filter chain used by the gateway and all five
business services.

**Not a service — a library.** No port, no `main`, no container.

## Contents

| Class | What |
|---|---|
| `SecurityConfiguration` | The one `SecurityFilterChain`, plus `@EnableMethodSecurity` |
| `JwtFilter` | `OncePerRequestFilter`, before `UsernamePasswordAuthenticationFilter` |
| `JwtClaimsParser` | HMAC verification, expiry check, claim extraction |
| `JwtTokenProvider` | Token issuance (used only by authorization-service) |
| `CustomUserPrincipal` | The `UserDetails` placed in the `SecurityContext` |
| `CurrentUserService` | Convenience accessor for the authenticated principal |
| `SecurityBeans` | Supporting beans |

## One chain, six consumers

The gateway runs on the **servlet** stack (`spring-cloud-starter-gateway-server-webmvc`), so
there is no reactive counterpart to keep in sync. There used to be — a separate
`ReactiveSecurityConfiguration` — and the two had drifted, leaving the gateway without
frame-options and CSP. Consolidating onto one chain fixed that.

`@ConditionalOnWebApplication(type = SERVLET)` guards it.

## Path rules — order matters

`requestMatchers` are evaluated **in declaration order**. Three groups:

1. **Public:** `/auth/**`, `/users/username/**`, `POST /users`, `/actuator/**`, `/error`,
   `/swagger-ui.html`, `/swagger-ui/**`, `/v3/api-docs/**`, `/api-docs/**`
2. **`USER` or `ADMIN`:** `/users/**`, `/accounts/**`, `/albums/**`, `/photos/**`
3. **`ADMIN`:** `/encrypt/**`, `/decrypt/**`, `/actuator/busrefresh`

Two entries deserve explanation:

- **`/error` must be permitted.** Servlet ERROR dispatch re-authorizes the failing request as
  `/error`; without the permit, every downstream failure comes back as a `401` and the real
  error is invisible.
- **The OpenAPI paths must stay above group 2.** They are anonymous by design (same treatment as
  `/actuator/health`). This publishes the endpoint *inventory* — not data — to anyone who can
  reach the port, which is acceptable while the stack is local-only.

Path rules are the outer layer. `@PreAuthorize` on individual controller methods narrows further
— e.g. `/users/**` is `USER or ADMIN` by path, but `GET /users` is `ADMIN` by annotation.

## How a request is authenticated

`JwtFilter` reads `Authorization: Bearer <token>`, has `JwtClaimsParser` verify the HMAC
signature and expiry, builds a `CustomUserPrincipal(userId, username, authorities)` and sets the
`SecurityContext`. Roles come from the `scope` claim and are prefixed with `ROLE_` if not already.

Validation is **local to each service** — no introspection call back to the authorization
service.

An expired token produces a `401` with `Expired JWT detected` at WARN. A malformed one logs at
ERROR and falls through unauthenticated, which the chain then rejects.

## Known limitation: shared HMAC secret

Every service holds the signing key, so every service can *mint* tokens, not just verify them.
That is acceptable for this round and explicitly recorded (decision 7).

The fix is not to build RSA/JWK here: the platform decision is **AWS Cognito**, after which this
library becomes a verify-only resource server configured with an `issuer-uri` and Cognito's JWKS
endpoint. See [`../../../docs/plans/backlog.txt`](../../../docs/plans/backlog.txt).

## Consumed by

The gateway and all five business services. Consumers must `@ComponentScan("com.photoapp.security")`
— this library has no auto-configuration yet, which is one of the recorded reuse blockers.

Note the gateway must also declare `spring-boot-starter-security` itself: this library needs
Boot's servlet security auto-configuration to supply the `HttpSecurity` bean it consumes.

## Future

One of the two libraries considered a genuine candidate for extraction into a reusable toolkit
(with [`photo-app-feign-lib`](../photo-app-feign-lib)). The blockers — hardcoded photo-app route
patterns, the `photoapp.*` property namespace, HMAC-only, no auto-configuration — are catalogued
in [`../../../docs/plans/dockerization-plan.md`](../../../docs/plans/dockerization-plan.md).

## See also

- [../../../docs/ARCHITECTURE.md](../../../docs/ARCHITECTURE.md) — the full security model
