# Dockerization Plan — Local Docker Profile

**Scope of this round:** local Docker only. Production/AWS is captured as backlog at the
end and is not elaborated here.

**Status:** analysis complete; all open questions resolved (see *Resolved decisions*).
Implementation runs as **Steps 0–9** in PART 2, one step at a time, each ending at an
explicit approval gate. Unit testing remains out of scope this round.

**Stack as analyzed:** Spring Boot `4.0.6`, Spring Cloud `2025.1.1`, Java `25`, Maven
multi-module reactor rooted at `photo-app-api/pom.xml`.

**Modules:**

| Group | Modules |
|---|---|
| Libraries | `photo-app-commons`, `photo-app-entity-model-lib`, `photo-app-feign-lib`, `photo-app-security-lib` |
| Services | `photo-app-authorization-service` (8085), `photo-app-users-service` (8081), `photo-app-accounts-service` (8082), `photo-app-albums-service` (8083), `photo-app-photos-service` (8084) |
| Infrastructure | `photo-app-api-gateway` (8080), `photo-app-configuration-server` (8888), `photo-app-discovery-service` (8761), `photo-app-discovery-service-cluster` (8761/8762/8763) |
| Schema | `photo-app-api/database` — standalone **Liquibase** Maven module |

**Schema migrations — confirmed decision:** the project uses **Liquibase**, handled by the
separate `photo-app-api/database` module for the whole system (one changelog set, not
per-service migrations). **Flyway is not used and will not be introduced.** The four
`spring.flyway.*` lines in `photo-app-users-service-prod.properties` are dead config with
no dependency behind them and should be deleted (Step 2).

---

# PART 1 — ANALYSIS

## 1. API Gateway architecture decision

### 1.1 What is in place today

`photo-app-api-gateway/pom.xml` uses:

```xml
<artifactId>spring-cloud-starter-gateway-server-webflux</artifactId>
<artifactId>spring-boot-starter-webflux</artifactId>
<artifactId>spring-boot-starter-webflux-test</artifactId>
```

The gateway is genuinely reactive: `photo-app-api-gateway-dev.properties` sets
`spring.main.web-application-type=reactive` and declares routes under the
`spring.cloud.gateway.server.webflux.routes[n]` prefix, all targeting `lb://` Eureka
service IDs.

Every other web module is **servlet/blocking**: `spring-boot-starter-webmvc` in
`photo-app-commons` and in all five services, Spring Data JPA + Hibernate everywhere,
blocking OpenFeign clients.

### 1.2 Where the "two security classes" problem actually shows up

| # | Location | Problem |
|---|---|---|
| 1 | `photo-app-security-lib/.../configuration/SecurityConfiguration.java` and `.../ReactiveSecurityConfiguration.java` | The **same 13-line URL rule table is maintained twice** (`/auth/**`, `/users/username/**`, `POST /users`, `/actuator/**`, `/users/**`, `/accounts/**`, `/albums/**`, `/photos/**`, `/encrypt/**`, `/decrypt/**`, `/actuator/busrefresh`, `anyRequest`). Selected at runtime by `@ConditionalOnWebApplication(SERVLET)` vs `(REACTIVE)` |
| 2 | Same two files | **They have already drifted.** The servlet chain adds `headers()` (frame-options DENY, CSP `script-src 'self'`) and `sessionManagement(sessionFixation().migrateSession())`. The reactive chain has neither. So the gateway — the only internet-facing component — is the one *missing* the clickjacking/CSP headers |
| 3 | `filter/JwtFilter.java` vs `filter/ReactiveJwtFilter.java` | Two implementations of identical claim-extraction logic, with **different failure semantics**. Servlet: on `ExpiredJwtException` clears the context and returns `401 "Token expired"`. Reactive: logs a warning and silently continues the chain — the request ends up 401 via `anyExchange().authenticated()`, but with a different (empty) response and no distinction between *expired* and *malformed* |
| 4 | `photo-app-security-lib/pom.xml` | Declares `spring-boot-starter-webflux` at **compile scope** so `ReactiveSecurityConfiguration` compiles. Consequence: Netty + Reactor are dragged onto the classpath of all five *servlet* services, which never use them. It also needs `jakarta.servlet-api` (provided) for the servlet side. The library is paying for both worlds in every consumer |
| 5 | `photo-app-security-lib/.../CurrentUserService.java` | Uses `SecurityContextHolder` (thread-local) — **servlet-only**. In a reactive context it silently returns `null`. Harmless today because the gateway does not use it, but it is a live trap: any ownership check moved to the gateway would fail open/closed unpredictably |
| 6 | `photo-app-feign-lib/.../FeignAuthInterceptor.java` | Uses `RequestContextHolder` + `HttpServletRequest` — servlet-only. Any future Feign call from the gateway would not propagate the `Authorization` header |
| 7 | Both security configs | Rules for `/encrypt/**` and `/decrypt/**` require `ROLE_ADMIN`, but those endpoints live on the **Config Server (:8888)**, which has *no* `spring-boot-starter-security` dependency at all. These are dead rules in both classes, and the real endpoints are unauthenticated (see §4) |
| 8 | `PhotoAppApiGatewayApplication.java` | `@ComponentScan(basePackages = {"com.photoapp.gateway", "com.photoapp.security"})` — the security library has no auto-configuration, so every consumer must remember this manual scan |

### 1.3 Recommendation — **migrate the Gateway to `spring-cloud-starter-gateway-server-webmvc`**

Reasons, strongest first:

1. **The gateway is the only reactive island in a fully blocking system.** JPA/Hibernate,
   servlet controllers, blocking Feign, thread-local `CurrentUserService`, servlet
   `FeignAuthInterceptor`, servlet `GlobalExceptionHandler`. WebFlux buys nothing
   downstream and costs a duplicated security model upstream.
2. **It collapses the duplication at the root.** One `SecurityFilterChain`, one
   `JwtFilter`, one rule table. Items 1, 2, 3, 5 above disappear rather than being
   "kept in sync".
3. **It removes WebFlux from `photo-app-security-lib`**, which removes Netty/Reactor
   from all five services (item 4).
4. **Java 25 + virtual threads** (`spring.threads.virtual.enabled=true`) removes most of
   the throughput argument for a reactive proxy at this scale. A blocking gateway on
   virtual threads handles a local/dev and small-production workload comfortably.
5. It matches the direction already written down in
   `photo-app-api/docs/aws-docker-questions.txt` ("Creo que tenía que haber escogido
   `spring-cloud-starter-gateway-server-webmvc`").

**Tradeoffs of switching (be aware of these):**

- Spring Cloud Gateway Server WebFlux remains the flagship implementation with the widest
  filter/predicate coverage. A few features (notably Redis-backed
  `RequestRateLimiter`, and some WebSocket/SSE proxying behavior) are WebFlux-first.
  None of them are used by this project today — the gateway is a plain path-router with
  five `lb://` routes.
- Per-request thread cost returns. Mitigated by virtual threads; irrelevant at local scale.
- Route configuration property prefix changes (`...server.webflux.routes` →
  `...server.webmvc.routes`), so the config repo file must be edited too.

**Migration effort: small — estimate 2–4 hours including a smoke test of all five routes.**

| File | Change |
|---|---|
| `infrastructure/photo-app-api-gateway/pom.xml` | `spring-cloud-starter-gateway-server-webflux` → `-webmvc`; `spring-boot-starter-webflux` → `spring-boot-starter-webmvc`; `spring-boot-starter-webflux-test` → `spring-boot-starter-webmvc-test` |
| `libraries/photo-app-security-lib/pom.xml` | Remove `spring-boot-starter-webflux` |
| `libraries/photo-app-security-lib/.../ReactiveSecurityConfiguration.java` | **Delete** |
| `libraries/photo-app-security-lib/.../ReactiveJwtFilter.java` | **Delete** |
| `libraries/photo-app-security-lib/.../SecurityConfiguration.java` | Drop `@ConditionalOnWebApplication(SERVLET)` (now the only chain) — optional, harmless to keep |
| config-repo `photo-app-api-gateway-dev.properties` | Remove `spring.main.web-application-type=reactive`; rename the five route blocks to the webmvc prefix |
| config-repo `photo-app-api-gateway-prod.properties` | Same rename for `spring.cloud.gateway.discovery.locator.*` if applicable |
| `PhotoAppApiGatewayApplication.java` | No change — `@ComponentScan` already picks up `com.photoapp.security` |

**One item to verify at implementation time, not assume:** the exact properties-based
route prefix accepted by `spring-cloud-starter-gateway-server-webmvc` in Spring Cloud
`2025.1.1` (`spring.cloud.gateway.server.webmvc.routes[n]...`). Confirm against the
2025.1.x reference docs / a 30-second boot test before rewriting all five route blocks.
`lb://` URIs work with the WebMVC gateway plus `spring-cloud-starter-loadbalancer`,
which is already a dependency.

**If you instead choose to keep WebFlux**, the consolidation work is: delete the servlet
`SecurityConfiguration` + `JwtFilter` from the library, port the missing `headers()`
hardening into `ReactiveSecurityConfiguration`, fix `ReactiveJwtFilter` to return 401 on
expiry, and then find a home for the servlet-only pieces the five services still need
(`SecurityConfiguration` would have to move *back* into each service or into a second
`security-lib-servlet` artifact). That is strictly more work than migrating the gateway,
which is the second argument for webmvc.

---

## 2. Micrometer usage

**Answer to the open question in `aws-docker-questions.txt`: Micrometer is used purely
for distributed tracing and JDBC auto-instrumentation. There are zero custom
application metrics.**

Exhaustive search results — **no** `@Timed`, **no** `MeterRegistry` injection, **no**
`Counter`/`Gauge`/`Timer` beans, **no** `micrometer-registry-prometheus` anywhere in
`src/main/java` or any pom.

### Dependency wiring

| Module | Micrometer-related dependencies |
|---|---|
| `api-parent/pom.xml` (BOM) | imports `net.ttddyy.observation:datasource-micrometer-bom:2.2.1` |
| `photo-app-users-service`<br>`photo-app-accounts-service`<br>`photo-app-albums-service`<br>`photo-app-photos-service`<br>`photo-app-authorization-service` | `spring-boot-micrometer-tracing-brave`, `spring-boot-starter-zipkin`, `io.micrometer:micrometer-tracing-bridge-brave`, `net.ttddyy.observation:datasource-micrometer-spring-boot` |
| `photo-app-api-gateway` | `io.micrometer:micrometer-observation`, `io.micrometer:micrometer-tracing-bridge-brave`, `io.zipkin.reporter2:zipkin-reporter-brave` |
| `photo-app-configuration-server` | none |
| `photo-app-discovery-service` | none |
| `photo-app-discovery-service-cluster` | none |
| all four libraries | none |

**Inconsistency to note:** the gateway uses raw `micrometer-observation` +
`zipkin-reporter-brave` instead of the `spring-boot-starter-zipkin` +
`spring-boot-micrometer-tracing-brave` pair used by the five services. Functionally
similar, but it means gateway tracing auto-configuration is assembled differently from
everything else — worth normalizing while touching the gateway pom anyway (§1.3).

### Property configuration

Identical two lines in `photo-app-api-gateway-dev.properties` and all five
`*-service-dev.properties`:

```properties
management.zipkin.tracing.endpoint=http://localhost:9411/api/v2/spans
management.tracing.sampling.probability=1.0
```

Not present in `photo-app-discovery-service-dev.properties` and not in the Config
Server's own `application.properties` — those two components emit no traces.

`management.endpoints.web.exposure.include=*` on the gateway and all five services means
`/actuator/metrics` is exposed; there is no Prometheus registry, so there is no scrape
endpoint and nothing is collecting those metrics today.

---

## 3. HikariCP configuration

**No `spring.datasource.hikari.*` property exists anywhere** — not in any module's
`src/main/resources`, not in any file in the Config Server repo (dev or prod), not in the
`database` module. All four JPA services run on **Spring Boot / HikariCP defaults**.

| Setting | Effective value (default) |
|---|---|
| `maximum-pool-size` | 10 |
| `minimum-idle` | 10 (defaults to `maximum-pool-size`) |
| `connection-timeout` | 30 000 ms |
| `idle-timeout` | 600 000 ms (inert while `minimum-idle == maximum-pool-size`) |
| `max-lifetime` | 1 800 000 ms (30 min) |
| `validation-timeout` | 5 000 ms |
| `auto-commit` | true |
| `pool-name` | `HikariPool-1` |

Services holding a datasource: `users`, `accounts`, `albums`, `photos`, `authorization`
— **five** services × 10 connections = **50 connections held open** against a single
`photo_app` schema, versus MySQL's default `max_connections=151`. Fine locally, but
wasteful in containers and a real ceiling once anything scales. Recommend setting an
explicit modest pool per service for the Docker profile (e.g. `maximum-pool-size=5`,
`minimum-idle=1`) — this is *business/tuning* config, so it belongs in the `dev`
properties files, not in a compose env var.

---

## 4. Sensitive properties audit

**Headline: nothing is encrypted. `{cipher}` appears zero times in the entire Config
Server repo.** The encryption machinery (keystore, `encrypt.keyStore.*`) is configured
and functional, but has never been applied to a value.

### 4.1 Values that should be encrypted with `/encrypt`

| Property | Files | Current value | Encrypted? |
|---|---|---|---|
| `photoapp.jwt.secret` | `photo-app-api-gateway-dev`, `photo-app-users-service-dev`, `-accounts-dev`, `-albums-dev`, `-photos-dev`, `-authorization-dev`, `-authorization-prod` (**7 files**) | `3q2+7w==Q29uU2VjdXJlS2V5U2FtcGxlMTIzNDU2Nzg5MDEyMzQ1Njc4OQ==` — the same HMAC signing key in all seven | ❌ plaintext, in git |
| `spring.datasource.password` | 5 dev files + 5 prod files | `password` / `prod_pass` | ❌ plaintext |
| `spring.datasource.username` | same 10 files | `photo_app_user` / `prod_user` | ❌ plaintext (low severity, encrypt for prod) |
| `spring.rabbitmq.password` | `users`, `accounts`, `albums`, `photos`, `authorization` dev + `authorization` prod | `guest` | ❌ plaintext |
| `spring.rabbitmq.username` | same | `guest` | ❌ plaintext |
| `spring.security.user.password` | `photo-app-discovery-service-dev.properties` | `password` (Eureka basic auth) | ❌ plaintext |
| `spring.security.user.name` | same | `eureka` | ❌ plaintext |
| `eureka.client.service-url.defaultZone` | config-repo `application.properties` (shared) | `http://eureka:password@localhost:8761/eureka` — **basic-auth credentials embedded in the URL** | ❌ plaintext. `{cipher}` covers a whole property value, so the entire URL string can be encrypted |

### 4.2 Values correctly handled as environment variables (cannot/should not be encrypted)

| Property | Where | Mechanism |
|---|---|---|
| `encrypt.keyStore.password` | config server `application.properties` | `${KEYSTORE_PASSWORD}` — correct; this *is* the key to the encryption, so it can never be a `{cipher}` value |
| `spring.cloud.config.server.git.username` | `application-git.properties` | `${GIT_USERNAME}` — correct |
| `spring.cloud.config.server.git.password` | `application-git.properties` | `${GIT_TOKEN}` — correct; this is the PAT |
| `eureka.client.service-url.defaultZone` | config server `application.properties` | `${EUREKA_URL}` — correct (also note: **no default**, so the Config Server will not start without it) |
| `spring.rabbitmq.username` / `password` | config server `application.properties` | `${RABBITMQ_USER}` / `${RABBITMQ_PASSWORD}` — correct |
| `spring.cloud.config.server.native.search-locations` | `application-native.properties` | `${CONFIG_REPO_PATH}` — correct |

### 4.3 Findings that outrank the encryption work

1. **The encryption keystore is committed to this repository.** `git ls-files` confirms
   both `photo-app-api/api-parent/infrastructure/photo-app-configuration-server/src/main/resources/keystore.jks`
   and `keystore.p12` are tracked. The root `.gitignore` does not exclude `*.p12`/`*.jks`.
   That private key is what all `{cipher}` values would be encrypted against — with it in
   git history, encrypting properties buys nothing.
   > **Decided (4):** keep the current keypair, but stop tracking it — `.gitignore` `*.p12`
   > / `*.jks`, `git rm --cached` both files (they stay on disk), never `COPY` the keystore
   > into the Config Server image, and bind-mount it at runtime instead. Note the residual
   > risk this accepts: the key remains in git *history*, so anyone with a clone of the
   > repository can still decrypt future `{cipher}` values. Rotating the keypair is the only
   > thing that closes that, and is deferred by choice.
2. **The Config Server has no Spring Security at all.** Its pom contains only actuator,
   `spring-cloud-config-server`, `spring-cloud-bus`, `spring-cloud-stream-binder-rabbit`.
   So on `:8888`, `/encrypt`, `/decrypt`, `/{app}/{profile}` (which returns every
   decrypted property, including passwords) and `/actuator/busrefresh` are all
   **unauthenticated**. The `ROLE_ADMIN` rules for `/encrypt/**` in the security library
   protect the wrong applications (see §1.2 item 7). The `keytool-commands.txt` note
   already flags this: *"Revisar spring security ya que usualmente solo ADMIN debe tener
   acceso"*.
   > **Decided (5):** in scope this round. Add `spring-boot-starter-security` with **HTTP
   > Basic** and a single env-var-driven admin user — not `photo-app-security-lib`, whose
   > JWT model targets human end-users through the gateway rather than services
   > authenticating at startup.
3. **One shared HMAC secret across all services** means every service can *mint* tokens,
   not just verify them. Acceptable for this round; noted as a design item for the
   generic security library (see §6).
4. Minor: the secret literal contains an embedded `==` padding sequence mid-string
   (`3q2+7w==Q29u...`), which is unusual for a single base64 blob. When you replace it
   with an encrypted value, generate a clean 256-bit key at the same time.

---

## 5. Config Server dual-profile state

The Config Server has its **own profile axis** (`native` | `git`), orthogonal to the
`dev` | `prod` axis used by every other service. Both are retained deliberately.

### `application.properties` (always applied)

```properties
spring.application.name=photo-app-configuration-server
spring.profiles.active=git          # <-- git is the built-in default
server.port=8888
encrypt.keyStore.location=classpath:/keystore.p12
encrypt.keyStore.password=${KEYSTORE_PASSWORD}
encrypt.keyStore.alias=mykey
eureka.client.service-url.defaultZone=${EUREKA_URL}
eureka.instance.prefer-ip-address=true
spring.rabbitmq.host=localhost      # <-- hardcoded, must be overridden in Docker
spring.rabbitmq.port=5672
spring.rabbitmq.username=${RABBITMQ_USER}
spring.rabbitmq.password=${RABBITMQ_PASSWORD}
management.endpoints.web.exposure.include=busrefresh,health,info
```

### Profile `git` — the default, and the one Docker will use

```properties
spring.cloud.config.server.git.uri=https://github.com/ossantvng-dev/photo-app-configuration-repo
spring.cloud.config.server.git.username=${GIT_USERNAME}
spring.cloud.config.server.git.password=${GIT_TOKEN}
spring.cloud.config.server.git.default-label=main
spring.cloud.config.server.git.search-paths=config-repo
spring.cloud.config.server.git.clone-on-start=true
spring.cloud.config.server.git.force-pull=true
```

- Backing store: **private** GitHub repo `ossantvng-dev/photo-app-configuration-repo`,
  branch `main`, properties under `config-repo/`.
- Requires `GIT_USERNAME` + `GIT_TOKEN` in the environment. `clone-on-start=true` means
  a bad/absent token is a **startup failure**, not a lazy failure — good for containers,
  but it makes the PAT a hard prerequisite.
- **This is the profile the docker-compose flow uses.**

### Profile `native` — kept intentionally for learning/reference

```properties
spring.cloud.config.server.native.search-locations=${CONFIG_REPO_PATH}
```

- Activated with `SPRING_PROFILES_ACTIVE=native` + `CONFIG_REPO_PATH` pointing at a
  filesystem directory (locally: `C:/Users/ossan/git/photo-app-configuration-repo/config-repo`).
- **Not flagged for removal.** It is the fastest way to iterate on properties without a
  commit/push cycle and it works with no network or token, which makes it valuable for
  offline work and for demonstrating how Config Server backends differ.
- Practical constraint to document: inside a container the `native` profile is only
  usable if the local config repo is bind-mounted in. The Docker path stays on `git`;
  `native` is a **host-only workflow**.

### Which is used where

| Context | Profile | Config source | Prerequisites |
|---|---|---|---|
| Native local run (IDE / `mvn spring-boot:run`), default | `git` | GitHub private repo | `GIT_USERNAME`, `GIT_TOKEN`, `KEYSTORE_PASSWORD`, `EUREKA_URL`, `RABBITMQ_USER/PASSWORD` |
| Native local run, offline / fast property iteration | `native` | `C:/Users/ossan/git/photo-app-configuration-repo/config-repo` | `CONFIG_REPO_PATH` + the same non-git env vars |
| **docker-compose (this round)** | `git` | GitHub private repo | dedicated fine-grained read-only PAT (see §Step 0) |
| Future AWS | `git` | GitHub private repo | PAT/secret from a secrets manager |

**Clients** are separate: services reach the Config Server via
`spring.config.import=optional:configserver:http://localhost:8888` in each module's
packaged `application.properties` — this is the property that must be overridden per
container.

---

## 6. Library modules — responsibilities and reuse blockers

### `photo-app-commons` — `com.photoapp.commons` / artifact `photo-app-commons`

**Responsibilities today**

- `configuration/ModelMapperConfig` — `ModelMapper` bean (skip-null, field matching, private access)
- `dto/` generic: `ApiErrorDTO`, `PaginationInputDTO`, `SortInputDTO`
- `dto/` domain: `account/*`, `album/*`, `photo/*`, `role/*`, `user/*`
- `exception/ApplicationException` + `exception/GlobalExceptionHandler` (`@RestControllerAdvice` covering `ApplicationException`, `MethodArgumentNotValidException`, `ConstraintViolationException`, `DataAccessException`, `OptimisticLockException`, catch-all)
- `util/FilterBuilderUtil`, `util/NormalizationUtil`, `util/PaginationUtil`

**Reuse blockers**

- 🚩 **Domain DTOs live inside a jar called "commons".** `AccountDTO`, `AlbumDTO`,
  `PhotoDTO`, `UserDTO`, `RoleDTO`, `AccountTypeDTO`, `RoleNameDTO`, `RoleAction`,
  `CreateAccountInputDTO` are photo-app business contracts. Any other project consuming
  `commons` inherits the photo-app domain model.
- 🚩 The pom pulls `spring-boot-starter-data-jpa` **and** `spring-boot-starter-webmvc`
  **and** `spring-boot-starter-validation` at compile scope — a "commons" library forces
  every consumer into JPA and Spring MVC.
- `GlobalExceptionHandler` binds `HttpServletRequest`, so it is servlet-only (fine given
  the §1.3 recommendation, but it is a hard constraint on reuse).
- No auto-configuration; consumers rely on package scanning to pick up the advice and the
  `ModelMapper` bean.

**Split suggested:** `commons-core` (error model, pagination/sorting, utils, ModelMapper —
no JPA dependency) + `photo-app-api-model` (the domain DTOs).

### `photo-app-entity-model-lib` — `com.photoapp.domain` / package `com.photoapp.entity`

**Responsibilities today**

- `BaseEntity` — `@MappedSuperclass` with identity `id`, `@Version`, `@CreatedDate` /
  `@LastModifiedDate` via `AuditingEntityListener`, `Hibernate.getClass()`-safe `equals`,
  constant `hashCode`
- Domain entities: `User`, `Role`, `RoleName`, `Account`, `AccountType`, `Album`, `Photo`
- Pom correctly scopes `spring-boot-starter-data-jpa` as `provided`

**Reuse blockers**

- 🚩 Everything except `BaseEntity` is photo-app domain.
- 🚩 **Architectural flag (beyond naming):** a *shared entity jar across microservices*
  means `users`, `accounts`, `albums`, `photos` and `authorization` all compile against
  the same table definitions. That is the classic distributed-monolith coupling — each
  service is supposed to own its schema. Reinforced by the fact that all five services
  point at the same `photo_app` database.
- 🚩 **Entities leak across the wire:** `UserFeignClient.findByUsernameAndActiveUser`
  returns the JPA `User` entity (not a DTO), so the persistence model is part of the
  inter-service contract.
- Not blocking for dockerization; recorded because it constrains the future toolkit.

**Extract:** `BaseEntity` + auditing config → `persistence-core`.

### `photo-app-feign-lib` — `com.photoapp.feign`

**Responsibilities today**

- `client/` — `UserFeignClient`, `AccountFeignClient`, `AlbumFeignClient`,
  `PhotoFeignClient`; each method carries `@Retry` + `@CircuitBreaker` with a `default`
  fallback that throws `ApplicationException(SERVICE_UNAVAILABLE)`
- `configuration/FeignConfiguration` — registers `ErrorDecoder` + `FeignAuthInterceptor`
- `decoder/CustomFeignErrorDecoder` — maps 401/403/404/503/other → `ApplicationException`
- `interceptor/FeignAuthInterceptor` — propagates the inbound `Authorization` header

**Reuse blockers**

- 🚩 The four client interfaces are 100% photo-app.
- 🚩 Resilience4j instance names are hardcoded strings that embed service names
  (`photo-app-users-service-isActive`, …) and must match the
  `resilience4j.*.instances.*` keys in the Config Server files — a naming contract split
  across two repositories.
- 🚩 `@FeignClient(name = "photo-app-users-service")` hardcodes Eureka service IDs.
- 🚩 `FeignAuthInterceptor` is servlet-only (`RequestContextHolder` / `HttpServletRequest`).
- ⚠️ `CustomFeignErrorDecoder` is annotated `@Component` **and** declared as a `@Bean` in
  `FeignConfiguration` — duplicate registration depending on what gets scanned.
- ⚠️ `HttpStatus.valueOf(response.status())` throws `IllegalArgumentException` on a
  non-standard status code, which would escape the decoder as an unmapped exception.

**Genuinely reusable:** `CustomFeignErrorDecoder`, `FeignAuthInterceptor`,
`FeignConfiguration`.

### `photo-app-security-lib` — `com.photoapp.security`

**Responsibilities today**

- `parser/JwtClaimsParser` — HS256 verify, `scope` claim → `ROLE_`-prefixed authorities,
  explicit expiry check
- `provider/JwtTokenProvider` — issues tokens (`sub`, `username`, `scope`, `iat`, `exp`)
- `model/CustomUserPrincipal` — `UserDetails` carrying `userId` + `username` + authorities
- `service/CurrentUserService` — current user/id/username/authorities, `isAdmin()`,
  `isUser()`, `canAccessResource(ownerId)` (**the ownership primitive**)
- `filter/JwtFilter` (servlet) + `filter/ReactiveJwtFilter` (reactive)
- `configuration/SecurityConfiguration` (servlet) + `ReactiveSecurityConfiguration` (reactive)
- `configuration/SecurityBeans` — `BCryptPasswordEncoder`, placeholder `UserDetailsService`

This is the strongest prototype for the future generic security library
(see `PLATFORM-VISION.md`).

**Reuse blockers**

- 🚩 **The URL rule table is hardcoded inside the library.** `/users/**`, `/accounts/**`,
  `/albums/**`, `/photos/**`, `/auth/**`, `POST /users` are photo-app routes baked into a
  reusable artifact. Must become externalized configuration (e.g.
  `@ConfigurationProperties("security.rules")` with a list of pattern/method/roles) so a
  consuming app declares its own matchers.
- 🚩 **App-branded property namespace:** `@Value("${photoapp.jwt.secret}")` and
  `${photoapp.jwt.validity}`. Should be `security.jwt.*` bound via
  `@ConfigurationProperties` with sane defaults, not raw `@Value` with no default
  (currently a missing property is a context-startup failure in *every* consumer).
- 🚩 **HMAC-only, shared-secret.** Every service holds the signing key and can therefore
  mint tokens, not just verify them. A reusable library needs an RSA/JWK verify-only mode
  for resource servers, with issuance confined to the authorization server.
- 🚩 **Two security models + `spring-boot-starter-webflux` at compile scope** — resolved by
  the §1.3 Gateway decision.
- ⚠️ **No auto-configuration.** No `@AutoConfiguration`, no
  `META-INF/spring/…AutoConfiguration.imports`. Consumers must manually
  `@ComponentScan("com.photoapp.security")` (the gateway does exactly this). A real
  library auto-configures and lets consumers back out with `@ConditionalOnMissingBean`.
- ⚠️ `SecurityBeans.userDetailsService()` throwing `UsernameNotFoundException("JWT only")`
  is a workaround; `photo-app-users-service` additionally sets
  `spring.autoconfigure.exclude=…UserDetailsServiceAutoConfiguration`. Two different
  workarounds for the same problem.
- ⚠️ `CustomUserPrincipal.getPassword()` is annotated `@NonNull` but always returns `null`
  when built by the filters.

---

## 7. Property audit for containerization

### 7.1 How the override works (and why no `docker` profile is needed)

Spring Boot's property precedence puts **OS environment variables above config data**
(`application.properties`, and anything imported via `spring.config.import`, including
Config Server responses). So a compose `environment:` entry beats the value the Config
Server serves for the same key, with `SPRING_PROFILES_ACTIVE=dev` still active. That is
what makes "one `dev` profile + env-var overrides" work without a second profile file.

Relaxed binding: uppercase, `.` → `_`, remove `-`. Note this applies to
`SystemEnvironmentPropertySource` name resolution, so it also works for values read with
plain `@Value("${photoapp.jwt.secret}")`, not only `@ConfigurationProperties`.

### 7.2 Shared file — config-repo `application.properties`

| Property | Current value | Class | Env var |
|---|---|---|---|
| `eureka.client.service-url.defaultZone` | `http://eureka:password@localhost:8761/eureka` | **Network + secret** | `EUREKA_CLIENT_SERVICEURL_DEFAULTZONE` |

### 7.3 Business services — `users` / `accounts` / `albums` / `photos` / `authorization`

All five `*-dev.properties` files are structurally identical. Ports: users 8081,
accounts 8082, albums 8083, photos 8084, authorization 8085.

| Property | Class | Env var to set in docker-compose |
|---|---|---|
| `spring.config.import` *(from the module's packaged `application.properties`, value `optional:configserver:http://localhost:8888`)* | **Network** | `SPRING_CONFIG_IMPORT=configserver:http://photo-app-config-server:8888` — drop `optional:` so a missing Config Server fails loudly |
| `spring.datasource.url` = `jdbc:mysql://localhost:3306/photo_app?allowPublicKeyRetrieval=true&useSSL=false` | **Network** | `SPRING_DATASOURCE_URL` |
| `spring.datasource.username` = `photo_app_user` | **Network/secret** | `SPRING_DATASOURCE_USERNAME` |
| `spring.datasource.password` = `password` | **Secret** | `SPRING_DATASOURCE_PASSWORD` |
| `spring.rabbitmq.host` = `localhost` | **Network** | `SPRING_RABBITMQ_HOST` |
| `spring.rabbitmq.port` = `5672` | **Network** | `SPRING_RABBITMQ_PORT` |
| `spring.rabbitmq.username` = `guest` | **Secret** | `SPRING_RABBITMQ_USERNAME` |
| `spring.rabbitmq.password` = `guest` | **Secret** | `SPRING_RABBITMQ_PASSWORD` |
| `management.zipkin.tracing.endpoint` = `http://localhost:9411/api/v2/spans` | **Network** | `MANAGEMENT_ZIPKIN_TRACING_ENDPOINT` |
| `eureka.client.service-url.defaultZone` *(inherited from shared file)* | **Network** | `EUREKA_CLIENT_SERVICEURL_DEFAULTZONE` |
| `eureka.instance.prefer-ip-address` = `true` | **Network behavior** | `EUREKA_INSTANCE_PREFER_IP_ADDRESS` — see note below |
| *(not set today)* `eureka.instance.hostname` | **Network** | `EUREKA_INSTANCE_HOSTNAME=<container name>` — see note below |
| *(new)* logging output directory | **Container path** | `LOG_BASE` — see §Step 3 |
| `photoapp.jwt.secret` | Secret (not location-dependent) | `PHOTOAPP_JWT_SECRET` if not encrypting yet |
| `spring.datasource.driver-class-name` | Business | — |
| `server.port` | Business (fixed per service) | — |
| `eureka.instance.instance-id` = `${spring.application.name}:${server.port}` | Derived | — |
| `spring.jpa.hibernate.ddl-auto` / `show-sql` / `format_sql` | Business | — |
| `management.endpoints.web.exposure.include` / `endpoint.health.show-details` / `health.circuitbreakers.enabled` | Business | — |
| `spring.cloud.openfeign.circuitbreaker.enabled` | Business | — |
| all `resilience4j.*` (aspect order, default configs, ~14 named instances) | Business | — |
| `management.tracing.sampling.probability` | Business | — |
| `photoapp.jwt.validity` | Business | — |
| `photoapp.albums.limits.basic` *(albums only)* | Business | — |
| all `logging.level.*` | Business | — |

**Note on `eureka.instance.prefer-ip-address`:** container IPs *are* routable inside a
compose network, so leaving it `true` works. Setting it to `false` plus
`EUREKA_INSTANCE_HOSTNAME=<container name>` produces a far more readable Eureka dashboard
and survives container restarts (which change IPs). Flagged as an open question.

### 7.4 API Gateway — `photo-app-api-gateway-dev.properties`

| Property | Class | Env var |
|---|---|---|
| `spring.config.import` *(packaged)* | **Network** | `SPRING_CONFIG_IMPORT` |
| `management.zipkin.tracing.endpoint` | **Network** | `MANAGEMENT_ZIPKIN_TRACING_ENDPOINT` |
| `eureka.client.service-url.defaultZone` *(inherited)* | **Network** | `EUREKA_CLIENT_SERVICEURL_DEFAULTZONE` |
| `eureka.instance.prefer-ip-address` | **Network behavior** | `EUREKA_INSTANCE_PREFER_IP_ADDRESS` / `EUREKA_INSTANCE_HOSTNAME` |
| ⚠️ **RabbitMQ — not configured at all** in this file, despite `spring-cloud-starter-bus-amqp` being on the gateway's classpath. It silently falls back to Boot defaults `localhost:5672` / `guest`. **This will break in Docker** | **Network + secret** | `SPRING_RABBITMQ_HOST`, `SPRING_RABBITMQ_PORT`, `SPRING_RABBITMQ_USERNAME`, `SPRING_RABBITMQ_PASSWORD` |
| `photoapp.jwt.secret` | Secret | `PHOTOAPP_JWT_SECRET` if not encrypting yet |
| `spring.main.web-application-type=reactive` | Framework | removed by the §1.3 migration |
| `spring.cloud.gateway.discovery.locator.enabled=false` | Business | — |
| `spring.cloud.gateway.…routes[0..4]` → `lb://PHOTO-APP-*-SERVICE` | Business — resolved via Eureka, **not** location-dependent | — |
| `server.port=8080` *(packaged)* | Business (fixed) | — |
| `eureka.client.registry-fetch-interval-seconds=5` | Business | — |
| `management.endpoints.web.exposure.include=*` | Business | — |
| `management.tracing.sampling.probability` | Business | — |
| `photoapp.jwt.validity` | Business | — |
| `logging.level.org.springframework.cloud.gateway*` | Business | — |

### 7.5 Discovery Service — `photo-app-discovery-service-dev.properties`

| Property | Current | Class | Env var |
|---|---|---|---|
| `spring.config.import` *(packaged)* | `optional:configserver:http://localhost:8888` | **Network** | `SPRING_CONFIG_IMPORT` |
| `eureka.instance.hostname` | `localhost` | **Network** | `EUREKA_INSTANCE_HOSTNAME=photo-app-discovery-service` |
| `eureka.client.service-url.defaultZone` | `http://${eureka.instance.hostname}:${server.port}/eureka` (self-referencing; the service-specific file wins over the shared `application.properties`) | **Network** | `EUREKA_CLIENT_SERVICEURL_DEFAULTZONE` (or let it follow `EUREKA_INSTANCE_HOSTNAME`) |
| `spring.security.user.name` | `eureka` | **Secret** | `SPRING_SECURITY_USER_NAME` |
| `spring.security.user.password` | `password` | **Secret** | `SPRING_SECURITY_USER_PASSWORD` |
| `server.port` | `8761` | Business (fixed) | — |
| `eureka.client.register-with-eureka` / `fetch-registry` | `false` | Business | — |

### 7.6 Config Server — its own packaged `application.properties` (not in the config repo)

| Property | Current | Class | Env var |
|---|---|---|---|
| `spring.profiles.active` | `git` | Mode | `SPRING_PROFILES_ACTIVE=git` (set explicitly for clarity) |
| `spring.cloud.config.server.git.uri` | GitHub URL | **Network** | `SPRING_CLOUD_CONFIG_SERVER_GIT_URI` (optional) |
| `spring.cloud.config.server.git.username` | `${GIT_USERNAME}` | **Secret** | `GIT_USERNAME` *(already env)* |
| `spring.cloud.config.server.git.password` | `${GIT_TOKEN}` | **Secret** | `GIT_TOKEN` *(already env — the new fine-grained PAT)* |
| `encrypt.keyStore.password` | `${KEYSTORE_PASSWORD}` | **Secret** | `KEYSTORE_PASSWORD` *(already env)* |
| `eureka.client.service-url.defaultZone` | `${EUREKA_URL}` | **Network** | `EUREKA_URL` *(already env; no default — mandatory)* |
| `spring.rabbitmq.host` | `localhost` **hardcoded** | **Network** | `SPRING_RABBITMQ_HOST` |
| `spring.rabbitmq.port` | `5672` | **Network** | `SPRING_RABBITMQ_PORT` |
| `spring.rabbitmq.username` / `password` | `${RABBITMQ_USER}` / `${RABBITMQ_PASSWORD}` | **Secret** | *(already env)* |
| `encrypt.keyStore.location` | `classpath:/keystore.p12` | **Container path** | `ENCRYPT_KEYSTORE_LOCATION=file:/run/secrets/keystore.p12` once the keystore stops being baked into the jar (§4.3) |
| `server.port` | `8888` | Business (fixed) | — |
| `management.endpoints.web.exposure.include` | `busrefresh,health,info` | Business | — |

### 7.7 Container / host mapping summary

| Compose service | Image / module | Container port | Replaces `localhost` reference |
|---|---|---|---|
| `photo-app-mysql` | `mysql:8.4` | 3306 | `spring.datasource.url` |
| `photo-app-liquibase` | `photo-app-api/database` (one-shot, `restart: "no"`) | — | `db.host` / `db.port` Maven properties |
| `photo-app-rabbitmq` | `rabbitmq:3-management` | 5672 / 15672 | `spring.rabbitmq.host` |
| `photo-app-zipkin` | `openzipkin/zipkin` | 9411 | `management.zipkin.tracing.endpoint` |
| `photo-app-config-server` | `photo-app-configuration-server` | 8888 | `spring.config.import` |
| `photo-app-discovery-service` | `photo-app-discovery-service` | 8761 | `eureka.client.service-url.defaultZone` |
| `photo-app-api-gateway` | `photo-app-api-gateway` | 8080 | — (public entry point) |
| `photo-app-users-service` … `-authorization-service` | respective modules | 8081–8085 | — (reached via `lb://` + Eureka) |
| `elasticsearch` / `logstash` / `kibana` | `docker.elastic.co/*:8.13.0` | 9200 / 5044,9600 / 5601 | — |

---

# PART 2 — IMPLEMENTATION CHECKLIST

> Steps are executed **in order**. Each step ends with a summary and an explicit approval
> gate before the next one starts.

## Step 0 — Prerequisites

- [ ] **Generate the dedicated GitHub PAT** — fine-grained, **read-only**, `Contents: Read`
      permission, **scoped only to `photo-app-configuration-repo`**, separate from the
      broader MCP token. Needed before Step 7, not before. Store it in a gitignored `.env`
      and pass it as `GIT_TOKEN`; never hardcode it in `docker-compose.yml`.
- [ ] Add `.env`, `*.p12` and `*.jks` to the root `.gitignore`.

## Step 1 — Gateway webmvc migration (§1.3) — foundational, do first ✅ DONE

- [x] Verify the properties route prefix for `spring-cloud-starter-gateway-server-webmvc`.
      Confirmed from the jar's own `spring-configuration-metadata.json` in
      `spring-cloud-gateway-server-webmvc:5.0.1`: **`spring.cloud.gateway.server.webmvc.routes`**
      — an exact mirror of the webflux prefix, so the config-repo rename is a literal
      `webflux` → `webmvc`.
- [x] Swap gateway pom: `-gateway-server-webflux` → `-gateway-server-webmvc`,
      `starter-webflux` → `starter-webmvc`, `starter-webflux-test` → `starter-webmvc-test`.
- [x] **Add `spring-boot-starter-security` to the gateway.** Not in the original plan and
      not optional: the gateway never declared it, because `@EnableWebFluxSecurity` supplies
      `ServerHttpSecurity` on its own. The servlet chain instead needs Boot's security
      auto-configuration to contribute the `HttpSecurity` bean, and without the starter the
      context fails with *"Parameter 0 of method securityFilterChain … required a bean of
      type HttpSecurity"*. The five business services already declare it; the gateway now
      matches them.
- [x] Delete `ReactiveSecurityConfiguration.java` and `ReactiveJwtFilter.java` from
      `photo-app-security-lib`.
- [x] Remove `spring-boot-starter-webflux` from `photo-app-security-lib/pom.xml`, replacing
      it with **`spring-boot-starter`**. The webflux starter was silently supplying
      `spring-boot-autoconfigure` (for `@ConditionalOnWebApplication` /
      `@ConditionalOnMissingBean`) and SLF4J; dropping it outright broke compilation. The
      plain starter restores both without dragging a web stack into consumers.
- [x] **Permit `/error` in `SecurityConfiguration`.** On the servlet stack any downstream
      failure triggers an `ERROR` dispatch that is re-authorized as `/error`; with
      `anyRequest().authenticated()` that turned every backend failure into a misleading
      **401**. WebFlux has no error dispatch, so this could not surface before the
      migration. Verified: `/auth/**` went from a false 401 to the real error once permitted.
- [x] Update `photo-app-api-gateway-dev.properties` in the config repo: drop
      `spring.main.web-application-type`, rename the five route blocks, and retarget the
      route-matching TRACE logger from the webflux class
      (`…gateway.handler.RoutePredicateHandlerMapping`, which no longer exists) to
      `…gateway.server.mvc.handler`.
- [x] Add the missing RabbitMQ properties to `photo-app-api-gateway-dev.properties`
      (§7.4 — was absent, defaulted to `localhost`).
- [x] Enable `spring.threads.virtual.enabled=true` on the gateway. *(Still open for the five
      services — deliberately not changed in this step.)*
- [x] Smoke test all five routes through `:8080` against a native local run. Run in
      isolation (no Eureka, no Config Server, properties fed from the config-repo file) with
      an HS256 token minted from the shared secret. **All five routes registered and
      resolved to the right backend**, evidenced by one load-balancer line per route:
      `No servers available for service: PHOTO-APP-{USERS,ALBUMS,PHOTOS,ACCOUNTS,AUTHORIZATION}-SERVICE`.
      Unauthenticated requests to the four protected routes returned 401 before routing.
      *(End-to-end with live backends still to be done once the stack runs — Step 8.)*
- [x] Confirm the divergences the merge exposes are now single-behavior. Verified on the
      wire: the gateway's responses now carry `X-Frame-Options: DENY` and
      `Content-Security-Policy: script-src 'self'` — the hardening it never had under the
      reactive chain — and the servlet `JwtFilter`'s 401-on-expiry is the only expiry
      semantics left.

**Two observations from the smoke test, deferred to `backlog.txt`:** the 500-instead-of-503
rendering of backend-unavailable failures (low priority), and Spring Cloud Function's web
handler shadowing `/actuator/<sub-path>` (pre-existing across all five services; scheduled
into Step 4).

> Note: `spring-boot-starter-webflux` remains on the gateway's classpath transitively via
> `spring-cloud-starter-bus-amqp` — identical to all five business services. Boot still
> deduces `SERVLET` because `DispatcherServlet` is present, confirmed by Tomcat 11 starting
> on `:8080`. No exclusion needed.

## Step 2 — Config Server security + keystore un-tracking ✅ DONE (encryption deferred)

**Basic Auth (decision 5):**

- [x] Add `spring-boot-starter-security` to `photo-app-configuration-server`.
- [x] Single admin user from `CONFIG_SERVER_ADMIN_USER` / `CONFIG_SERVER_ADMIN_PASSWORD`,
      bound through `spring.security.user.name` / `.password` so Boot's in-memory user is
      driven entirely by the environment and no credential is committed.
      **`photo-app-security-lib` deliberately not used** — wrong consumer model (decision 5).
- [x] Filter chain in `com.photoapp.config.server.configuration.SecurityConfiguration`:
      `/actuator/health` and `/actuator/health/**` permitted anonymously, `anyRequest()`
      authenticated — which covers `/encrypt/**`, `/decrypt/**`, `/{app}/{profile}` and
      `/actuator/busrefresh` without enumerating them.
- [x] CSRF disabled, `SessionCreationPolicy.STATELESS`, HTTP Basic.
- [x] All **seven** config clients send the credentials via
      `spring.cloud.config.username` / `spring.cloud.config.password` in their packaged
      `application.properties`. (Seven, not eight: `photo-app-discovery-service-cluster`
      has no `spring.config.import` and is not in the compose stack — decision 6.)
- [x] Verified natively — see the evidence table below.

**Keystore (decision 4):**

- [x] Added `*.p12`, `*.jks` and `.env` to `.gitignore`; `git rm --cached` on
      `keystore.p12` and `keystore.jks` — untracked, still on disk. **Current keypair kept.**
- [ ] The Config Server `Dockerfile` must **not** `COPY` the keystore (Step 6).
      ⚠️ **Not sufficient on its own.** `keystore.p12` lives in `src/main/resources`, so
      `mvn package` bakes it into the fat jar and any image built from that jar contains it.
      To honour "never baked into the image", the **`.dockerignore` must exclude `*.p12` /
      `*.jks`** so the build context has no keystore and the jar built inside the image is
      clean. Local native runs keep working because the local build still packages it.
- [ ] `docker-compose.yml` bind-mounts the host keystore into the container (Step 7).
      With the planned layered extraction the app runs from a real directory, so the mount
      target is `…/BOOT-INF/classes/keystore.p12` and `encrypt.keyStore.location` can stay
      `classpath:/keystore.p12` unchanged.

**Property encryption — deliberately deferred.** Not started: it must come after every
config-repo property edit is final (Steps 3, 4 and 8 all still add properties), and it
needs `KEYSTORE_PASSWORD`, which only you hold. Checklist retained below.

**Step 2 verification (Config Server on `:8888`, native profile, Basic Auth active):**

| Request | No credentials | Valid credentials | Wrong password |
|---|---|---|---|
| `/actuator/health` | 503 *(reachable — DOWN only because Rabbit was off)* | 503 | — |
| `/actuator/info` | **401** | 200 | — |
| `/actuator/busrefresh` | **401** | — | — |
| `/photo-app-users-service/dev` | **401** | 200 | **401** |
| `/encrypt/status` | **401** | reached *(500: keystore disabled for this test)* | — |
| `/decrypt` | **401** | — | — |

Gateway as a client: with the env vars set it authenticated and logged
`Located environment: name=photo-app-api-gateway, profiles=[dev]`, then served its routes.
Without them it got 401 from the server.

**Property encryption** (apply once all config-repo property edits are final — re-verify at
Step 8):

- [ ] Generate a clean 256-bit JWT signing key to replace the current literal.
- [ ] Encrypt with `/encrypt` and replace with `{cipher}…` in the config repo, in this order:
  - [ ] `photoapp.jwt.secret` (7 files)
  - [ ] `spring.datasource.password` + `spring.datasource.username` (10 files)
  - [ ] `spring.rabbitmq.username` + `spring.rabbitmq.password` (6 files)
  - [ ] `spring.security.user.name` + `spring.security.user.password` (discovery-dev)
  - [ ] `eureka.client.service-url.defaultZone` in the shared `application.properties`
        (whole-URL encryption, credentials included)
- [ ] Delete the four dead `spring.flyway.*` lines from
      `photo-app-users-service-prod.properties` — the project uses Liquibase and there is
      no Flyway dependency, so they are misleading no-ops.
- [ ] Verify every service still boots natively after encryption, before dockerizing.

## Step 3 — Tracing + logback parity for gateway / discovery / config-server ✅ DONE

- [x] Add `spring-boot-starter-zipkin` + `spring-boot-micrometer-tracing-brave` to
      `photo-app-api-gateway`, `photo-app-discovery-service` and
      `photo-app-configuration-server`. All three also needed
      `net.logstash.logback:logstash-logback-encoder`, without which the new
      `logback-spring.xml` cannot resolve its encoder.
- [x] **Normalized** the gateway: dropped raw `micrometer-observation`,
      `micrometer-tracing-bridge-brave` and `zipkin-reporter-brave` in favour of the two
      Boot starters (§2 inconsistency resolved).
- [x] Add `management.zipkin.tracing.endpoint` + `management.tracing.sampling.probability`
      — config repo for the discovery service (which also had **no** actuator exposure, now
      `health,info`); packaged `application.properties` for the Config Server, which cannot
      import from itself; the gateway already had both.
- [x] Add a `logback-spring.xml` to each of the three, matching the five services' file.
- [x] Log-base change applied to **all eight** files. The five services keep
      `${LOG_BASE:-api-parent/services}`; the three infrastructure components use
      `${LOG_BASE:-api-parent/infrastructure}` so the native layout mirrors the module tree.
      Either way `LOG_BASE` collapses all eight onto one path in Docker.
- [x] Verified natively against live MySQL, RabbitMQ and Zipkin — see below.

**Step 3 verification (full eight-component native run):**

| Check | Result |
|---|---|
| All 8 components started | ✅ config-server, discovery, gateway, users, accounts, albums, photos, authorization |
| Eureka registrations | ✅ 6 — five services + gateway (the Eureka server does not self-register; the Config Server has no Eureka client at all, see `backlog.txt`) |
| Authenticated traffic through the gateway | ✅ `/users/1`, `/albums/1`, `/photos/1`, `/accounts/1` all **200** |
| Services reporting spans to Zipkin | ✅ **all 8**, including the three that previously emitted nothing |
| Trace propagation | ✅ one `traceId` spanning `photo-app-api-gateway` → `photo-app-users-service` |
| JSON log files | ✅ 8 files, each with `traceId` / `spanId` / `service` / `environment` fields |
| `LOG_BASE` env override | ✅ restarting the gateway with `LOG_BASE=<tmp>` redirected its log to `<LOG_BASE>/photo-app-api-gateway/logs/…` — the mechanism the Step 7 named volume depends on |

## Step 4 — HikariCP sizing (§3) ✅ DONE

- [x] Explicit sizing added to each of the five service `*-dev.properties`:
      `pool-name=<service>-pool`, `maximum-pool-size=5`, `minimum-idle=1`,
      `connection-timeout=30000`, `idle-timeout=300000`, `max-lifetime=1800000`.
      Previously nothing was set anywhere, so five services × the 10-connection default
      allowed 50 connections; the cap is now 25 against a server whose `max_connections`
      is 151. Pools are named per service so logs and metrics identify the owner.
- [x] Committed and pushed to `main` (`e3a9cb0`); the running Config Server picked the
      values up through `force-pull`.
- [x] Verified at runtime — all five services logged `<service>-pool - Starting...`,
      `/actuator/metrics/hikaricp.connections.max` reported **5.0** on every service, and
      MySQL showed 11 live connections for `photo_app_user` instead of the ~50 the old
      defaults permitted.

> ~~Set `spring.cloud.function.web.enabled=false`~~ — **dropped, it is a no-op.**
> `spring-cloud-function-web` is not on any module's classpath (only
> `spring-cloud-function-context`, via `spring-cloud-stream-binder-rabbit`). Measured on
> the gateway with and without the flag: identical results. The Step 1 reading that it
> helped was a false inference — the improvement came from permitting `/error` in the same
> sitting. Adding it would have been exactly the kind of dead config deleted from the
> Config Server. See `backlog.txt` for the corrected entry and the measured behaviour.

## Step 5 — MapStruct migration in `photo-app-commons` ✅ DONE

- [x] MapStruct `1.6.3` replaces ModelMapper in the parent's properties and dependency
      management; `mapstruct-processor` and `lombok-mapstruct-binding` `0.2.0` added to
      `annotationProcessorPaths`, the binding sitting **between** lombok and
      mapstruct-processor so Lombok-generated accessors are visible to MapStruct.
- [x] `@Mapper` interfaces written, all with `componentModel = "spring"` and
      `unmappedTargetPolicy = ReportingPolicy.ERROR` so an unmapped target fails the build:
  - in `photo-app-commons`: `RoleMapper` (`Role`↔`RoleDTO`, `RoleName`↔`RoleNameDTO`),
    `UserMapper` (`User`→`UserDTO`, delegating roles to `RoleMapper`), `AccountMapper`
    (`Account`→`AccountDTO`, `CreateAccountInputDTO`→`Account`,
    `AccountType`↔`AccountTypeDTO`), `AlbumMapper`, `PhotoMapper`
  - in the owning services: `UserInputMapper`, `AlbumInputMapper`, `PhotoInputMapper`
- [x] `ModelMapperConfig` deleted, plus the four per-service `*MapperConfig` classes that
      registered `typeMap` overrides. The `modelmapper` dependency is gone from the parent
      and from `photo-app-commons`.
- [x] All **28** call sites rewired across four services.
- [x] Full reactor build passes; verified natively — see below.

**Two structural facts that shaped the result:**

1. **`photo-app-commons` did not depend on `photo-app-entity-model-lib`.** Mappers need
   both sides of a pair, so the dependency was added. Safe: `entity-model-lib` has no
   internal dependencies, so no cycle is possible. This does mean `commons` now carries the
   JPA entity library — consistent with the accepted coupling in the non-goal section.
2. **Not every mapper can live in `commons`.** `CreateUserInputDTO`, `CreateAlbumInputDTO`
   and `CreatePhotoInputDTO` are owned by their services; `commons` cannot reference them
   without a cycle. Their mappers therefore live in the owning service. Everything whose
   DTO already lives in `commons` is mapped there, as intended.

Also worth knowing: MapStruct builds entities through Lombok's `@Builder`, which does not
expose inherited `BaseEntity` fields. `id` / `version` / `createdAt` / `updatedAt` are
consequently unreachable from any input mapper **by construction** — the old explicit
`mapper.skip(...)` calls are structurally unnecessary now.

### Step 5 verification — behavioural diff against a pre-migration baseline

Live responses were captured **before** the migration and re-captured after, same data,
same endpoints:

| Endpoint | Result |
|---|---|
| `GET /users/1` | identical |
| `GET /users?page=0&size=3` | identical |
| `GET /albums/1` | identical |
| `GET /photos/1` | identical |
| `GET /accounts/1` | **differs — a bug fix, see below** |

**The one difference is a defect ModelMapper was hiding.** `AccountDTO` names the field
`accountTypeDTO` while `Account` names it `accountType`; ModelMapper's fuzzy matching never
bridged it and silently produced `null`, so the account type never reached the API even
though the database held `PREMIUM`:

```
before: {"id":1,...,"accountTypeDTO":null,   "activeAccount":true,...}
after:  {"id":1,...,"accountTypeDTO":"PREMIUM","activeAccount":true,...}
```

MapStruct's `ReportingPolicy.ERROR` would have refused to compile the mapper without an
explicit `@Mapping(source = "accountType", target = "accountTypeDTO")` — exactly the class
of silent failure the migration was meant to eliminate.

Write paths were exercised too: `POST /users` produced a bcrypt hash (60 chars, `$2a$10$`),
`activeUser = true` from `@Builder.Default`, and the default `ROLE_USER`; `POST /accounts`
persisted `account_type = PREMIUM` and round-tripped it. Both test rows were deleted after
verification.

## Step 6 — Dockerfiles ✅ DONE

Delivered as **one** `photo-app-api/Dockerfile` with three stages, parameterised by build
args, rather than eight near-identical files. Each image selects its module with
`MODULE_PATH` / `JAR_NAME` / `SERVICE_PORT`, so the expensive reactor build is a single
shared stage BuildKit builds once and reuses.

- [x] Root `.dockerignore` — excludes `target/`, `logs/`, `.git/`, IDE files **and
      `**/*.p12` / `**/*.jks`**. The keystore exclusion is load-bearing, not hygiene: the
      keystore lives in `src/main/resources`, so without it Maven packages it into
      `BOOT-INF/classes` and every image ships the private key. Confirmed by extracting a
      locally built jar, which *does* contain `BOOT-INF/classes/keystore.p12`.
- [x] Multi-stage: build on **eclipse-temurin 25 JDK**, runtime on **amazoncorretto:25**.
- [x] Reactor constraint handled — the build stage builds the whole reactor
      (`mvn -f api-parent/pom.xml install`), since the four libraries must be installed
      before any service resolves them. A BuildKit cache mount keeps `~/.m2` between builds
      and never becomes an image layer.
      **Built from `api-parent/pom.xml`, not the root pom** — the root reactor includes the
      `database` module, whose `liquibase:update` is bound to `process-resources` and would
      try to reach MySQL during the image build.
- [x] Spring Boot 4 layered extraction, verified against 4.0.6 rather than assumed:
      `java -Djarmode=tools -jar app.jar extract --launcher --layers --destination extracted`
      (`layertools` is gone; the four layers are `dependencies`, `spring-boot-loader`,
      `snapshot-dependencies`, `application`, copied least- to most-volatile).
      The destination must be a **subdirectory** — extracting into the working directory
      fails with *"already exists and is not empty"* because `app.jar` sits there.
- [x] Non-root `photoapp` user (uid 999); `-XX:MaxRAMPercentage=75` plus
      `-XX:+ExitOnOutOfMemoryError`.
- [x] `HEALTHCHECK` on `/actuator/health`, with `SERVICE_PORT` promoted from `ARG` to `ENV`
      so it resolves at runtime.
- [x] `LOG_BASE=/var/log/photo-app` baked in, directory pre-created and owned by the app
      user, ready for the Step 7 shared volume.
- [x] All **8 images** build: config-server, discovery, gateway, users, accounts, albums,
      photos, authorization. `discovery-service-cluster` is not containerised (decision 6).
- [ ] Liquibase migration container — deferred to Step 7, where it belongs with the
      database service it depends on.

### Step 6 verification

| Check | Result |
|---|---|
| All 8 images build | ✅ |
| Container runs | ✅ launches via `JarLauncher`, reaches Tomcat, fails only on the absent datasource/config-server — correct in isolation |
| Runs as non-root | ✅ `uid=999(photoapp)` |
| Log dir writable by app user | ✅ |
| **No keystore in any image** | ✅ filesystem scan finds no `*.p12` / `*.jks`; the Config Server image contains its properties and `logback-spring.xml` only |
| **Keystore bind mount works** | ✅ containerised Config Server with the keystore mounted **only** at `/app/BOOT-INF/classes/keystore.p12`: `/actuator/health` 200 unauthenticated, config 401 without credentials and 200 with, and a full `/encrypt` → `/decrypt` round-trip |
| Step 4 config reaches containers | ✅ served `spring.datasource.hikari.maximum-pool-size: 5` |

Two deviations worth noting: the build stage uses the `maven:3.9-eclipse-temurin-25` tag —
that image *is* eclipse-temurin 25 JDK with Maven preinstalled, and this repo has no Maven
wrapper to use on the bare JDK image. And `curl` is **not** installed in the runtime stage:
Amazon Linux 2023 ships `curl-minimal`, and installing the full `curl` package fails with a
package conflict. The preinstalled binary serves the healthcheck.

---

## Step 6 (original outline — superseded by the record above)

- [ ] Add a root `.dockerignore` (`target/`, `.git/`, `.idea/`, `*.log`, `.env`).
- [ ] **Multi-stage builds**: build stage on `eclipse-temurin:25-jdk`, runtime stage on
      `amazoncorretto:25`. (Mixing OpenJDK distributions across stages is fine — both are
      binary-compatible OpenJDK builds.)
- [ ] Note the reactor constraint: the four libraries must be installed into the local Maven
      repository before any service module can build, so each build stage must build from
      the reactor root (or use a shared, pre-populated dependency layer) rather than from
      the module directory alone.
- [ ] Use Spring Boot 4 layered extraction (`java -Djarmode=tools -jar app.jar extract --layers`)
      — verify the exact flag set for `4.0.6`; the old `layertools` jarmode is gone.
- [ ] Run as a non-root user; set `-XX:MaxRAMPercentage=75`.
- [ ] Add a `HEALTHCHECK` hitting `/actuator/health` (open on every service and the gateway;
      the Config Server leaves `health` anonymous per Step 2; the Discovery Service already
      permits `/actuator/health` anonymously).
- [ ] One Dockerfile per deployable: config-server, discovery-service, api-gateway, users,
      accounts, albums, photos, authorization (**8 images**). The
      `photo-app-discovery-service-cluster` module is **not** containerized (decision 6).
- [ ] The Config Server image must **not** contain the keystore (decision 4).
- [ ] Package the **Liquibase** `photo-app-api/database` module as a one-shot migration
      container. Two viable shapes — pick at implementation time:
      **(a)** a `maven:…-eclipse-temurin-25` image running `mvn liquibase:update` with
      `-Ddb.host=photo-app-mysql -Ddb.username=… -Ddb.password=…` (the pom already
      parameterizes `db.host`/`db.port`/`db.name`/`db.username`/`db.password`, defaulting to
      `localhost:3306`), or
      **(b)** the official `liquibase/liquibase` image with
      `src/main/resources/db/changelog` mounted and `changelog-master.xml` as the entry
      point — lighter, no Maven in the runtime image.
      Either way it must be `restart: "no"` and gated on the MySQL healthcheck.
      Note the pom binds `liquibase:update` to `process-resources`, so a plain reactor build
      would also try to migrate — keep the migration container the only thing that talks to
      the database, and do not build this module inside the service images.

## Step 7 — docker-compose: infrastructure ✅ DONE

Delivered `photo-app-api/docker-compose.yml` (infrastructure only — application services
are Step 8), `photo-app-api/.env.example`, `photo-app-api/docker/elk/`, and
`photo-app-api/tools/local/setup.sh`.

- [x] Network `photo-app-net`; volumes for MySQL data, ES data, certs, the shared
      `photo-app-logs`, a persisted Logstash sincedb, and a Maven cache.
- [x] `photo-app-mysql` (mysql:8.4) on **3306**, `photo-app-rabbitmq` on **5672/15672**,
      `photo-app-zipkin` on **9411** — dedicated containers, standard ports (decision 3).
      All three healthchecked and reaching `healthy`.
- [x] `photo-app-liquibase` one-shot, gated on MySQL health, `restart: "no"`.
- [x] Local bootstrap script `tools/local/setup.sh` with `--status` / `--drop` modes.
- [x] ELK moved in-repo to `docker/elk/`, **`xpack.security.enabled=true`**, TLS, a
      two-stage bootstrap, authenticated Logstash output, persisted sincedb.

**Liquibase container — chose the Maven shape, not `liquibase/liquibase`.** The official
image ships **no MySQL driver** (it failed with *"Cannot find database driver:
com.mysql.jdbc.Driver"*, having guessed the legacy class from the URL). Adding a driver
would pin the connector version in a second place; running the module's own
`liquibase-maven-plugin` keeps its pom the single source of truth for both changelogs and
the `mysql-connector-j` version. A named Maven cache volume keeps reruns fast.

**ELK bootstrap is two one-shot stages, not one.** Certificates must exist *before*
Elasticsearch starts, but `kibana_system` has no usable password until the cluster is
running — a single container cannot do both. So `photo-app-elk-certs` (generates CA +
server cert, idempotent) runs first, then Elasticsearch, then `photo-app-elk-users` (sets
the `kibana_system` password), then Kibana. Certificate SANs cover `elasticsearch`,
`photo-app-elasticsearch` and `localhost`.

**`XPACK_MONITORING_ENABLED=false` on Logstash.** Its licence/monitoring reader keeps a
separate connection that defaults to `http://elasticsearch:9200` and cannot work once TLS
is enforced; it logged a repeating "Elasticsearch Unreachable" error while the pipeline
itself was fine. The pipeline output is configured independently and is unaffected.

### Step 7 verification

| Check | Result |
|---|---|
| `docker compose config` | ✅ valid |
| MySQL / RabbitMQ / Zipkin | ✅ all `healthy` |
| Liquibase into an empty DB | ✅ **12 of 12 changesets applied** |
| Seed data present | ✅ 100 users, 2 roles, 199 accounts, 597 albums, 5 970 photos, 100 user_roles |
| Migration idempotent | ✅ rerun: `Run: 0, Previously run: 12` |
| Elasticsearch | ✅ `healthy` with security + TLS |
| Plain HTTP to ES | ✅ **refused** — TLS enforced |
| HTTPS without credentials | ✅ **401** |
| HTTPS with `elastic` | ✅ 200 |
| Kibana | ✅ `healthy`, `/api/status` 200 — proves `kibana_system` auth works |
| Logstash → ES | ✅ authenticated over TLS, pipeline running |
| **Log ingestion end to end** | ✅ a JSON file written into `photo-app-logs` appeared in index `photoapp-logs-2026.08.01`, searchable by `traceId` |
| **sincedb fix** | ✅ restart produced **no duplicate** (1 doc before, 1 after); `sincedb_photoapp` persisted with the correct byte offset |
| `tools/local/setup.sh --status` | ✅ "is up to date" |
| `tools/local/setup.sh` | ✅ no-op on an already-migrated database |

Note on the script: it must `cd` into the `database` module before invoking Maven. The
pom's `searchPath` is the relative `src/main/resources`, which Liquibase resolves against
the **current directory**, not the pom's location — running with `-f` from elsewhere fails
with *"changelog-master.xml was not found in the configured search path"*.

---

## Step 7 (original outline — superseded by the record above)

**Backing services** (standard ports — the standalone host containers are not running,
decision 3):

- [ ] Create network `photo-app-net`.
- [ ] `photo-app-mysql` — **new, empty** database (do **not** reuse the standalone
      `mysql-v8` container). `MYSQL_DATABASE=photo_app`, `MYSQL_USER=photo_app_user`,
      `MYSQL_PASSWORD`, `MYSQL_ROOT_PASSWORD`; named volume `photo-app-mysql-data`;
      healthcheck via `mysqladmin ping`; published on **3306**.
- [ ] `photo-app-rabbitmq` — `rabbitmq:3-management`, dedicated; healthcheck via
      `rabbitmq-diagnostics check_running`; published on **5672** / **15672**.
- [ ] `photo-app-zipkin` — `openzipkin/zipkin`, dedicated; published on **9411**.
- [ ] `photo-app-liquibase` — one-shot migration container (Step 6) that builds the
      `photo_app` schema **from scratch** into the empty database, including the six seed
      changelogs. Runs after `photo-app-mysql` is healthy, before any application service.

**Local bootstrap script (requirement D):**

- [ ] `photo-app-api/tools/local/setup.*` — create the schema/user if missing and run the
      Liquibase changelogs against a **native** local MySQL; usable independently of Docker
      and as a complement to the init container. No production equivalent this round.

**ELK, in-repo, secured (requirement C + Phase-5 work):**

- [ ] Move the stack from `C:\Users\ossan\workspaces\docker\elk` into `./docker/elk/`
      (compose fragment + `logstash/pipeline/pipeline.conf`).
- [ ] Replace the host bind mount `…/api-parent/services:/mnt/services` with the **named
      volume** `photo-app-logs`, mounted into all eight app containers and into Logstash;
      point the `pipeline.conf` input glob at the new path.
- [ ] `xpack.security.enabled=true`. Generate TLS certificates for ES/Kibana/Logstash
      (self-signed via `elasticsearch-certutil` or the `docker-elk` setup-container
      pattern); mount the CA into Logstash and Kibana.
- [ ] `elastic` and `kibana_system` passwords via environment variables only, never
      hardcoded; Logstash's ES output authenticates with user + password + CA.
- [ ] Replace `sincedb_path => "/dev/null"` with a persisted sincedb on its own volume — as
      written, every Logstash restart re-ingests every log file and duplicates documents.
- [ ] Document the certificate/user bootstrap steps in the ELK README (Step 9) — this is
      deliberate groundwork for production.

## Step 8 — docker-compose: application services

- [ ] Every app service keeps `SPRING_PROFILES_ACTIVE=dev`. **No `docker` profile.**
      (Exception: the Config Server uses its own orthogonal axis and gets
      `SPRING_PROFILES_ACTIVE=git`.)
- [ ] **Eureka registration is hostname-based** (decision 2):
      `EUREKA_INSTANCE_PREFER_IP_ADDRESS=false` +
      `EUREKA_INSTANCE_HOSTNAME=<container name>` on every registering component.
- [ ] `photo-app-config-server`: `GIT_USERNAME`, `GIT_TOKEN` (the new PAT),
      `KEYSTORE_PASSWORD`, `CONFIG_SERVER_ADMIN_USER`, `CONFIG_SERVER_ADMIN_PASSWORD`,
      `SPRING_RABBITMQ_HOST/PORT`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD`;
      keystore bind mount; depends on `photo-app-rabbitmq` (healthy).
      **No `EUREKA_URL`** — the Config Server is deliberately not registered with Eureka
      (clients reach it by direct URL via `spring.config.import`, so registering it would
      only add a bootstrap chicken-and-egg problem). Its Eureka properties were deleted.
- [ ] `photo-app-discovery-service`: `SPRING_CONFIG_IMPORT` (with Basic-Auth credentials),
      `EUREKA_INSTANCE_HOSTNAME`, `EUREKA_CLIENT_SERVICEURL_DEFAULTZONE`,
      `SPRING_SECURITY_USER_NAME/PASSWORD`. Single instance — the cluster module is not
      included (decision 6).
- [ ] Five business services + gateway: apply the §7.3 / §7.4 env var tables verbatim, plus
      `LOG_BASE=/var/log/photo-app` and the `photo-app-logs` volume mount.
- [ ] Prefer `spring.cloud.config.fail-fast=true` + config retry over compose `depends_on`
      ordering — Config Server availability is a startup race that `depends_on` alone does
      not solve reliably.
- [ ] Drop `optional:` from `SPRING_CONFIG_IMPORT` in containers so a missing Config Server
      is a loud failure rather than a service silently booting with no properties.
- [ ] Publish **only** the gateway (`8080`), plus Kibana (`5601`), RabbitMQ management
      (`15672`), Zipkin (`9411`) and the Eureka dashboard (`8761`) for convenience.
- [ ] **Commit and push** every config-repo edit to `main` (decision 8) — `force-pull=true`
      against `main` means unpushed local edits are invisible to the Config Server.
- [ ] Verify end to end: Eureka shows all six registrations by hostname → login through the
      gateway → one request per route → traces from all eight components in Zipkin → logs in
      Kibana (authenticated) → `/actuator/busrefresh` propagates a config change.

## Step 9 — Documentation

- [ ] `README.md` per module (8 services + 4 libraries + the ELK stack): purpose, port,
      dependencies, required env vars, how to run natively, how to run in Docker, endpoints.
      The ELK README carries the certificate/user bootstrap procedure (requirement C).
- [ ] `photo-app-api/docs/ARCHITECTURE.md`: component diagram, request flow (client →
      gateway → service → Feign → service), config flow (Config Server → git repo → Bus
      refresh), security flow (login → JWT → filter → role rules), observability flow
      (logback → Logstash → ES → Kibana; Micrometer → Zipkin), profile and
      environment-variable strategy.
- [ ] Cross-link the new docs from the existing notes in `photo-app-api/docs/`.

---

# Resolved decisions

All questions raised by the analysis have been answered. Recorded here so they are not
re-litigated.

> **Schema tool.** The project uses **Liquibase** via the separate `photo-app-api/database`
> module for the whole system. Flyway is not used and will not be introduced. The plan runs
> that module as a one-shot migration container (Step 6 / Step 7).

1. **Docs location — resolved.** Planning docs live in **`photo-app-api/docs/plans/`**
   alongside the existing reference notes. The root `./docs/` directory has been removed;
   `ARCHITECTURE.md` also goes under `photo-app-api/docs/`.
2. **Eureka registration mode — resolved: hostname-based.** Set
   `eureka.instance.prefer-ip-address=false` and pass
   `EUREKA_INSTANCE_HOSTNAME=<container name>` per service in compose.
3. **Host ports — resolved: standard ports, no shifting.** The standalone `mysql-v8`,
   RabbitMQ and Zipkin containers on the host are **not running**. The new dedicated
   containers publish `3306`, `5672`/`15672` and `9411`.
4. **Keystore — resolved: keep the current keypair, stop tracking it.**
   - Do **not** regenerate the keys.
   - Add `*.p12` and `*.jks` to `.gitignore`; `git rm --cached keystore.p12 keystore.jks`
     so both become untracked but stay on disk.
   - The Config Server `Dockerfile` must **not** `COPY` the keystore into the image.
   - `docker-compose.yml` bind-mounts the host file to the classpath location the app
     expects, so the key is available at runtime without ever being baked into an image
     or committed.
5. **Config Server security — resolved: in scope this round, HTTP Basic.**
   Add `spring-boot-starter-security` to `photo-app-configuration-server` with a single
   admin user supplied via `CONFIG_SERVER_ADMIN_USER` / `CONFIG_SERVER_ADMIN_PASSWORD`.
   Protect `/encrypt/**`, `/decrypt/**`, `/{app}/{profile}` and `/actuator/busrefresh`;
   leave `/actuator/health` open for container healthchecks.
   **Do not reuse `photo-app-security-lib` here** — that library is JWT-based and designed
   for human end-users authenticating through the gateway. The Config Server is consumed
   by other Spring services at startup, a different consumer model that HTTP Basic fits
   correctly.
6. **Discovery topology — resolved: single instance.** Only
   `photo-app-discovery-service` goes into the compose stack.
   `photo-app-discovery-service-cluster` stays in the codebase for learning/reference and
   is **not** containerized — same treatment as the Config Server's `native` profile.
7. **JWT scheme — resolved: keep the shared HMAC secret** across all services this round.
   RSA/JWK verify-only is a backlog item for the future generic security library.
8. **Config repo changes — resolved: in scope.** All edits to
   `photo-app-configuration-repo` (route prefix rename, new env-var-driven properties,
   Hikari settings, tracing config for gateway/discovery/config-server) must be
   **committed and pushed to `main`** — the `git` profile uses `default-label=main` with
   `force-pull=true`, so unpushed local edits are invisible to the Config Server.
9. **Logback — resolved: option (b), extended to all eight components.** Make the log base
   overridable, and additionally **add a `logback-spring.xml` to the three components that
   lack one** (gateway, discovery service, config server), matching the five services'
   pattern and writing into the shared named volume. Done in the same pass as the tracing
   parity work below, since both touch the same three modules.

---

# Additional requirements (beyond the original analysis)

### A. Tracing + logging parity for the three infrastructure components

`photo-app-api-gateway`, `photo-app-discovery-service` and `photo-app-configuration-server`
emit **no traces** and have **no `logback-spring.xml`** today. Both gaps are closed:

- Add the same tracing dependencies the five business services use —
  `spring-boot-starter-zipkin` + `spring-boot-micrometer-tracing-brave` — and the same
  `management.zipkin.tracing.endpoint` / `management.tracing.sampling.probability`
  properties.
- While in the gateway's pom, **normalize** its tracing dependencies to that same pattern
  instead of the current raw `micrometer-observation` + `zipkin-reporter-brave` pair
  (the inconsistency flagged in §2).
- Add a `logback-spring.xml` to each of the three, matching the existing service file and
  adjusted for the shared named volume (Step 3).

### B. Replace ModelMapper with MapStruct in `photo-app-commons`

- Remove `ModelMapperConfig` and the `modelmapper` dependency.
- Add MapStruct with its annotation processor wired into the Maven compiler plugin.
- Write explicit `@Mapper` interfaces for every current Entity↔DTO pair: `User`, `Role`,
  `RoleName`, `Account`, `AccountType`, `Album`, `Photo`, plus nested/composite DTOs such
  as `CreateAccountInputDTO`.
- Update every call site that currently injects the `ModelMapper` bean to use the
  generated mapper instead.

Rationale: compile-time mapping removes the reflective bean, makes every field mapping
explicit and reviewable, and turns mapping mistakes into build failures instead of runtime
surprises.

### C. ELK with security enabled

The original plan deferred this. It is now in scope:

- `xpack.security.enabled=true` (not `false`).
- Generate TLS certificates for the Elasticsearch / Kibana / Logstash cluster —
  self-signed is fine locally (`elasticsearch-certutil`, or the standard `docker-elk`
  security setup pattern).
- `elastic` superuser and `kibana_system` service-account passwords supplied via
  environment variables, never hardcoded.
- Logstash's Elasticsearch output must authenticate (user + password + CA cert).
- Document the certificate and user bootstrap steps in the ELK component README — this
  adds real operational complexity versus the security-disabled setup, and is deliberate
  groundwork for production, where the same model is required.

### D. Local database bootstrap script

Add a convenience script under `photo-app-api/tools/local/` for bootstrapping the local
database (same spirit as `kanan-system-api/tools/local/setup.bash`): create the schema/user
if needed and run the Liquibase changelogs. It must work for **native, non-Docker local
runs** and complement the Docker init-container path. **No production equivalent** — out of
scope this round.

---

# Explicit non-goal — library generalization

`photo-app-commons`, `photo-app-feign-lib` and `photo-app-entity-model-lib` are **not** to
be made generic or cross-project reusable in this round. The reuse blockers listed in §6 —
domain DTOs living in `commons`, one shared entity library across microservices pointing at
a single database, hardcoded Feign client names — are **accepted, deliberate architectural
decisions for this project**, not defects.

Only `photo-app-security-lib` and `photo-app-feign-lib` are expected to generalize
eventually, and only as a separate later initiative. No work toward that goal happens now
beyond what is already specified here (the Gateway migration and the Config Server
security work).

---

# Backlog — Production Phase (out of scope this round)

Not elaborated here; captured so nothing is lost.

- **Network:** dedicated VPC; private subnets for services, public only for the gateway;
  security groups per port/CIDR; Elastic IP on the gateway only.
- **Compute:** ECS Fargate task definitions per service; service discovery strategy
  (Cloud Map vs Eureka cluster on ECS).
- **Persistence:** RDS MySQL instead of containerized MySQL; encrypted credentials in
  Config Server / AWS Secrets Manager; explicit HikariCP sizing per task.
- **Security:** true OAuth2 **PKCE** flow (the current
  `AuthorizationServerConfig` registers an in-memory PKCE client but the login path is a
  custom JWT endpoint); **role-level security at the Gateway**; RSA/JWK signing with
  verify-only resource servers (decision 7 keeps shared HMAC for now); promote the Config
  Server's Basic Auth (delivered in Step 2) to mTLS or a secrets-manager-backed credential.
- **Observability:** ELK retention policies and index lifecycle management; CA/cert rotation
  and a real (non-self-signed) certificate chain — `xpack.security` itself is **enabled this
  round** (requirement C). Add a Prometheus registry and actual application metrics (§2 —
  none exist today).
- **Library generalization:** extract the generic parts of `photo-app-security-lib` and
  `photo-app-feign-lib` into a reusable toolkit. Explicitly **not** this round, and
  explicitly **not** `photo-app-commons` / `photo-app-entity-model-lib`, whose current
  coupling is an accepted decision — see the non-goal section above.
- **Alerting:** token-expiry alerts and Feign-failure alerts — the first consumer of the
  planned generic Alert framework (`PLATFORM-VISION.md`).
- **Configuration:** complete the `*-prod.properties` files, which are currently
  placeholders (`prod-db`, `prod_user`, `prod_pass`, several still pointing at
  `localhost:8761`, and `photo-app-api-gateway-prod.properties` missing route definitions
  entirely).
- **Eureka:** decide production topology (multi-AZ peer cluster vs managed discovery).
- **CI/CD — this backlog should feed a GitHub Actions pipeline** covering: build the
  reactor (libraries first), run tests, build and push the eight images to ECR, run the
  Liquibase migration against RDS as a deploy step, and deploy to ECS. Unit testing is deliberately excluded from the
  current round but is a prerequisite for the `test` stage of that pipeline.
