# Architecture

A Spring Boot 4 / Spring Cloud 2025.1.1 microservices platform on Java 25, built as a single
Maven reactor and deployed as eight containers plus supporting infrastructure.

For where this is heading long-term — the generic Alert framework, the reusable library
extraction, the platform ambitions — see [plans/PLATFORM-VISION.md](plans/PLATFORM-VISION.md).
For how the current shape was arrived at, see [plans/dockerization-plan.md](plans/dockerization-plan.md).

---

## 1. The eight components

| Component | Port | Role |
|---|---|---|
| `photo-app-configuration-server` | 8888 | Serves externalized config from a private Git repo |
| `photo-app-discovery-service` | 8761 | Eureka server; service registry |
| `photo-app-api-gateway` | 8080 | Single public entry point; routing, security headers, Swagger aggregation |
| `photo-app-authorization-service` | 8085 | Issues, refreshes and revokes JWTs |
| `photo-app-users-service` | 8081 | Users, roles, activation |
| `photo-app-accounts-service` | 8082 | Accounts, account types |
| `photo-app-albums-service` | 8083 | Albums, per-user album limits |
| `photo-app-photos-service` | 8084 | Photos within albums |

Only two application ports are published to the host: the gateway (8080) and the Eureka
dashboard (8761). The five business services are reachable **only** through the gateway.

### Supporting infrastructure

| Container | Port | Role |
|---|---|---|
| `photo-app-mysql` | 3306 | MySQL 8; single `photo_app` schema shared by all five services |
| `photo-app-rabbitmq` | 5672 / 15672 | Spring Cloud Bus transport (`/actuator/busrefresh`) |
| `photo-app-zipkin` | 9411 | Distributed trace collector |
| `elasticsearch` | 9200 | Log storage, `xpack.security` enabled |
| `kibana` | 5601 | Log search UI |
| `logstash` | — | Tails the shared log volume, ships to Elasticsearch |

Plus four **one-shot init jobs** that run to completion and are then removed by
`tools/local/stack.sh`: `photo-app-liquibase` (schema migration), `photo-app-elk-certs`
(CA + certificates), `photo-app-elk-users` (ES built-in user passwords), and
`photo-app-logs-init` (chowns the shared log volume to uid 999).

---

## 2. How it fits together

```
                              ┌──────────────────────────┐
        HTTP :8080            │  photo-app-api-gateway   │
   client ─────────────────►  │  routing, CSP, frame-opts│
                              │  Swagger aggregation     │
                              └───────────┬──────────────┘
                                          │  lb://SERVICE-ID
                     ┌────────────────────┼────────────────────┐
                     ▼                    ▼                    ▼
        ┌────────────────────┐  ┌──────────────────┐  ┌──────────────────┐
        │ authorization :8085│  │   users :8081    │  │  accounts :8082  │
        └─────────┬──────────┘  └────────┬─────────┘  └────────┬─────────┘
                  │                      │  ▲                  │
                  │                      │  └──── Feign ───────┘
                  │                      │   UserFeignClient#isActive
                  │                      │
                  │             ┌────────┴─────────┐  ┌──────────────────┐
                  │             │   albums :8083   │  │   photos :8084   │
                  │             └────────┬─────────┘  └────────┬─────────┘
                  │                      │                     │
                  └──────────────────────┼─────────────────────┘
                                         ▼
                              ┌──────────────────────┐
                              │  photo-app-mysql     │  one shared schema
                              └──────────────────────┘

   Cross-cutting, every component:

   ┌────────────────────┐   config at startup    ┌──────────────────────────┐
   │  config-server     │ ◄───────────────────── │  all 7 other components  │
   │  :8888 (HTTP Basic)│                        └──────────────────────────┘
   └─────────┬──────────┘
             │ force-pull main
             ▼
   photo-app-configuration-repo (private GitHub)

   ┌────────────────────┐   register / discover  ┌──────────────────────────┐
   │  discovery :8761   │ ◄───────────────────── │  gateway + 5 services    │
   └────────────────────┘                        └──────────────────────────┘

   ┌────────────────────┐   spans                ┌──────────────────────────┐
   │  zipkin :9411      │ ◄───────────────────── │  all 8 components        │
   └────────────────────┘                        └──────────────────────────┘

   all 8 ──► JSON logs ──► shared volume ──► logstash ──► elasticsearch ──► kibana
```

### Startup order

Enforced in `docker-compose.yml` by `depends_on` + healthchecks, and it matters:

```
rabbitmq ──► config-server ──► discovery ──► gateway
                                    │
mysql ──► liquibase ────────────────┴──► the five business services
```

The Config Server is deliberately **not** registered with Eureka. Clients reach it by direct
URL, so registering it would only create a bootstrap chicken-and-egg problem.

### Service-to-service calls

There is exactly one synchronous inter-service dependency today:
**accounts → users**, via `UserFeignClient#isActive` in `photo-app-feign-lib`. It is wrapped in
a Resilience4j circuit breaker and retry (instance
`photo-app-users-service-isActive`: 10-call sliding window, 50% failure threshold, 3 attempts
with exponential backoff). `FeignAuthInterceptor` forwards the caller's JWT so the downstream
authorization check sees the original principal.

`photo-app-feign-lib` also declares `AccountFeignClient`, `AlbumFeignClient` and
`PhotoFeignClient` for calls that are wired but not yet exercised on a hot path.

---

## 3. Security model

### Token flow

```
1. POST /auth/login {username, password}
        │
        ▼  authorization-service
   CustomUserDetailsService loads the user, BCrypt-verifies the password
   JwtTokenProvider signs a JWT with the shared HMAC secret
        │
        ▼
2. {accessToken, refreshToken}
        │
        ▼
3. Every subsequent request:  Authorization: Bearer <accessToken>
        │
        ▼  gateway, then the target service — each independently:
   JwtFilter (OncePerRequestFilter, before UsernamePasswordAuthenticationFilter)
     └─ JwtClaimsParser.parse — verifies HMAC signature, checks expiry
     └─ builds CustomUserPrincipal(userId, username, authorities)
     └─ sets SecurityContextHolder
        │
        ▼
4. SecurityFilterChain path rules, then @PreAuthorize method rules
```

Tokens are **validated locally by every service** — there is no introspection call back to the
authorization service. The `scope` claim carries roles; `JwtClaimsParser` prefixes them with
`ROLE_` if not already prefixed.

### Where the rules live

`photo-app-security-lib` holds a **single** `SecurityFilterChain` used by the gateway and all
five business services (the gateway runs on the servlet stack via
`spring-cloud-starter-gateway-server-webmvc`, so there is no reactive variant to keep in sync).

Two layers, both active:

- **Path rules** in `SecurityConfiguration` — evaluated in declaration order.
  - Public: `/auth/**`, `/users/username/**`, `POST /users`, `/actuator/**`, `/error`,
    and the OpenAPI paths (`/swagger-ui/**`, `/v3/api-docs/**`, `/api-docs/**`).
  - `ROLE_USER` or `ROLE_ADMIN`: `/users/**`, `/accounts/**`, `/albums/**`, `/photos/**`.
  - `ROLE_ADMIN` only: `/encrypt/**`, `/decrypt/**`, `/actuator/busrefresh`.
- **Method rules** via `@EnableMethodSecurity` + `@PreAuthorize` on controller methods. These
  are strictly *narrower* than the path rules — e.g. `/users/**` is `USER or ADMIN` at the path
  level, but `GET /users` (list all) is `@PreAuthorize("hasRole('ADMIN')")`.

`/error` must stay permitted: without it, servlet ERROR dispatch re-authorizes the failing
request as `/error` and masks every downstream failure as a 401.

An `AccessDeniedException` handler in `GlobalExceptionHandler` (declared above the catch-all)
turns authorization failures into a **403** with an `ACCESS_DENIED` log line at WARN, rather
than the 500 they previously produced.

### Roles

Two, seeded by Liquibase: `ROLE_ADMIN` and `ROLE_USER`. See [DATABASE.md](DATABASE.md).

### Config Server security

HTTP Basic, single admin from `CONFIG_SERVER_ADMIN_USER` / `CONFIG_SERVER_ADMIN_PASSWORD`.
Deliberately **not** the JWT security-lib: that library is built for human end-users
authenticating through the gateway, whereas the Config Server is consumed by other Spring
services at startup — a different consumer model that HTTP Basic fits correctly.
`/actuator/health` stays open so Docker's healthcheck works.

Property encryption uses a PKCS#12 keystore. The keystore is **never** baked into an image and
**never** committed: `*.p12` / `*.jks` are gitignored and excluded in `.dockerignore`, and
`docker-compose.yml` bind-mounts the file into the container at runtime.

### PKCE client registration — what it is and is not

`AuthorizationServerConfig` registers an in-memory `RegisteredClient` (`photoapp-client`,
`AUTHORIZATION_CODE` grant, `ClientAuthenticationMethod.NONE`, redirect
`http://localhost:8080/callback`). That registration is shaped like a PKCE public client, but
**the actual login path is a custom JWT endpoint** (`POST /auth/login`), not an authorization
code + code_verifier exchange. No `code_challenge` is ever issued or verified.

This is a known, recorded simplification, not an oversight. The resolution is **not** to finish
building it: the platform decision is to adopt **AWS Cognito** as the identity provider and
become a verify-only resource server against its JWKS endpoint. See `plans/backlog.txt` and the
Security entry in the AWS backlog of [plans/dockerization-plan.md](plans/dockerization-plan.md).

### Known simplifications in the current round

- Shared HMAC secret: every service holds the signing key and can therefore *mint* tokens, not
  just verify them. Superseded by the Cognito decision above.
- The JWT secret lives in the config repo rather than a secrets manager.
- OpenAPI specs are anonymous, which publishes the endpoint inventory (not data) to anyone who
  can reach the port. Acceptable while the stack is local-only.

---

## 4. Observability

Three signals, correlated by one identifier.

### Tracing

Micrometer Tracing with a Brave bridge, `management.tracing.sampling.probability=1.0` (sample
everything — this is a dev stack). Spans go to Zipkin at `http://photo-app-zipkin:9411`.

`photo-app-tracing-lib` exists for one reason: Boot 4's default `ZipkinHttpClientSender` (built
on the JDK `java.net.http.HttpClient`) fails inside these containers with
`ClosedChannelException` on a long-lived client. The library contributes a
`BytesMessageSender` backed by `URLConnectionSender` instead, registered via
`@AutoConfiguration(before = ZipkinAutoConfiguration.class)` and guarded by
`@ConditionalOnMissingBean`. It is registered through
`META-INF/spring/…AutoConfiguration.imports` rather than component scanning, because the
config-server and discovery-service do not scan `com.photoapp`.

### Logging

Every component uses `logback-spring.xml` with `LogstashEncoder`, emitting JSON to both console
and a rolling file under `${LOG_BASE}/${serviceName}/logs/`. In containers `LOG_BASE` is
`/var/log/photo-app`, a volume shared by all eight. Logstash tails
`/var/log/photo-app/**/logs/*.log` and writes to the daily index `photoapp-logs-YYYY.MM.dd`.

Custom fields added by the encoder: `service` (from `spring.application.name`) and
`environment` (from `spring.profiles.active`).

### The correlation mechanism

Micrometer Tracing puts `traceId` and `spanId` into the SLF4J MDC. `LogstashEncoder` serializes
the whole MDC into the JSON event. So the *same* `traceId` appears in:

- the Zipkin span tree, and
- every log line from every service that participated in the request.

That gives you the round trip: find a slow or failing trace in Zipkin → copy its `traceId` →
`traceId:"<id>"` in Kibana returns every log line across all services for that one request, in
order. Or the reverse: find an error log in Kibana → take its `traceId` → open that trace in
Zipkin to see where the time went.

See [OBSERVABILITY.md](OBSERVABILITY.md) for the Kibana setup and ready-made queries, and
[LOGGING.md](LOGGING.md) for the per-environment level policy.

---

## 5. Configuration

Config lives in a separate private repo, `photo-app-configuration-repo`, under `config-repo/`.
The Config Server runs the `git` profile with `default-label=main` and `force-pull=true`, so
**local edits are invisible until pushed** — pushing is part of making a config change, not an
afterthought.

Layering, lowest precedence first:

1. `config-repo/application.properties` — shared by everything
2. `config-repo/<app>-<profile>.properties` — per service, per profile (`dev` / `prod`)
3. The module's own packaged `src/main/resources/application.properties` — identity
   (`spring.application.name`), the config-server import, and structural settings
4. **OS environment variables** — what `docker-compose.yml` uses to point each container at
   container hostnames instead of `localhost`

One important subtlety: `spring.config.import` is processed **per config-data document**, so an
env var cannot replace an import that is declared inside a packaged `application.properties`.
That is why the import is written as
`optional:configserver:${CONFIG_SERVER_URL:http://localhost:8888}` — the placeholder is
overridable, the import statement itself is not.

Runtime refresh without restart: `POST /actuator/busrefresh` (ADMIN) propagates over RabbitMQ
via Spring Cloud Bus.

---

## 6. Build and packaging

Single Maven reactor. `pom.xml` at the root aggregates `api-parent` and `database`;
`api-parent/pom.xml` carries the Boot parent, dependency management and the shared
annotation-processor chain (Lombok → `lombok-mapstruct-binding` → MapStruct → Hibernate
processor, in that order — the binding must sit between Lombok and MapStruct).

DTO ↔ entity mapping is **MapStruct**, with `unmappedTargetPolicy = ReportingPolicy.ERROR` so an
unmapped field is a compile error rather than a silent null. Shared mappers live in
`photo-app-commons`; mappers for input DTOs owned by a single service live in that service.

There is exactly **one** `Dockerfile`, parameterized by `MODULE_PATH`, `JAR_NAME` and
`SERVICE_PORT`. Three stages: build on `maven:3.9-eclipse-temurin-25`, a layer-extraction stage
using `java -Djarmode=tools -jar app.jar extract --launcher --layers`, and a runtime stage on
`amazoncorretto:25` running as non-root `photoapp` (uid 999).

Note the build stage targets `api-parent/pom.xml`, **not** the root pom — the root includes the
`database` module, whose `liquibase:update` is bound to `process-resources` and would try to
reach a database during an image build.

---

## 7. Module layout

```
photo-app-api/
├── Dockerfile                  one parameterized file for all 8 images
├── docker-compose.yml          17 services
├── pom.xml                     root reactor: api-parent + database
├── api-parent/
│   ├── libraries/
│   │   ├── photo-app-commons             DTOs, MapStruct mappers, GlobalExceptionHandler
│   │   ├── photo-app-entity-model-lib    JPA entities
│   │   ├── photo-app-feign-lib           Feign clients, auth interceptor, error decoder
│   │   ├── photo-app-security-lib        JWT filter, security chain, principal
│   │   └── photo-app-tracing-lib         Zipkin sender replacement
│   ├── services/                         the 5 business services
│   └── infrastructure/                   config-server, discovery, gateway,
│                                         discovery-cluster (reference only, not containerized)
├── database/                   Liquibase changelogs — the single source of schema truth
├── docker/elk/                 ELK bootstrap scripts and Logstash pipeline
├── tools/local/                setup.sh (database), stack.sh (compose lifecycle)
└── docs/                       this file and its siblings
```

The three libraries `photo-app-commons`, `photo-app-feign-lib` and `photo-app-entity-model-lib`
are **deliberately not generic**. They are photo-app-specific by decision, not by accident —
see the non-goal section of [plans/dockerization-plan.md](plans/dockerization-plan.md).

---

## See also

- [RUNNING.md](RUNNING.md) — running the system, three ways
- [DATABASE.md](DATABASE.md) — schema changes and Liquibase workflow
- [LOGGING.md](LOGGING.md) — log level policy per environment
- [OBSERVABILITY.md](OBSERVABILITY.md) — Kibana, Zipkin, and ready-made queries
- [plans/PLATFORM-VISION.md](plans/PLATFORM-VISION.md) — long-term direction
- [plans/dockerization-plan.md](plans/dockerization-plan.md) — how this shape was arrived at
- [plans/backlog.txt](plans/backlog.txt) — open items and recorded decisions
