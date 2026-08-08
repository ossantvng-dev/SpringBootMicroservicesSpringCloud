# Testing

How testing works in this project: where tests live, what to name them, how to run them, and the
one non-obvious trap that will silently make your tests meaningless.

This is the **living reference**. For the phased plan, the full inventory of what needs covering,
and current progress, see [plans/testing-plan.md](plans/testing-plan.md) — that document is the
planning record, this one is the day-to-day guide.

> **Current state:** Phases 1 (infrastructure), 2 (exception handling), 3 (the authorization
> matrix) and 4 (Feign and resilience) are complete — **527 tests**. Every controller has an
> authorization suite and the whole Feign layer is covered end to end; none of the controllers
> has a *behavioural* suite yet, so Phases 5–8 are outstanding and the service, repository and
> mapper layers are still untested. If you are adding the first test to a module, this document
> tells you where to put it; the plan tells you what is worth writing.

---

## 1. Architecture

Two pieces, and the split matters:

```
api-parent/libraries/photo-app-test-support/     ← fixtures ONLY, no @Test classes
    src/main/java/com/photoapp/test/support/
        security/     TestJwt, TestPrincipals, @WithMockPhotoAppUser
        fixtures/     TestEntities
        persistence/  MySqlContainerSupport
        web/          PhotoAppSecuritySliceConfig

<each module>/src/test/java/...                  ← the actual @Test classes live here
```

`photo-app-test-support` holds **shared fixtures**, published from `src/main/java` so consumers
can import them. It contains no tests of its own except `SecuritySliceControlTest`, which guards
the fixture it ships (see §6).

**Actual tests live in the module they test**, in that module's own `src/test/java`, importing
from test-support. Tests are never centralised into test-support — a test belongs next to the
code it describes.

Every consumer declares it at test scope, so none of it can reach a production image:

```xml
<dependency>
    <groupId>com.photoapp.test</groupId>
    <artifactId>photo-app-test-support</artifactId>
    <scope>test</scope>
</dependency>
```

**Three modules cannot use it:** `photo-app-commons`, `photo-app-security-lib` and
`photo-app-entity-model-lib`. test-support depends on all three, and Maven rejects module cycles
regardless of scope. Their suites use plain JUnit and build what they need locally.

See [../api-parent/libraries/photo-app-test-support/README.md](../api-parent/libraries/photo-app-test-support/README.md)
for what each fixture does and why.

---

## 2. Where tests go

Mirror `src/main/java` package-for-package:

```
src/test/java/com/photoapp/<service>/
├── controller/        UserControllerWebMvcTest.java
├── service/           UserServiceImplTest.java
├── repository/        UserRepositoryIT.java
│   └── specification/ UserSpecificationTest.java
├── mapper/            UserMapperTest.java
└── support/           fixtures specific to THIS module only
src/test/resources/
└── application-test.properties
```

Put something in `support/` only if it is genuinely local to one module. If two modules need it,
it belongs in test-support instead.

---

## 3. Naming — the suffix decides when it runs

| Suffix | Meaning | Plugin | Maven phase |
|---|---|---|---|
| `*Test` | Fast. No Docker, no containers. | surefire | `test` |
| `*IT` | Needs Testcontainers or a full application context. | failsafe | `verify` |

This is not a style preference — the suffix is wired into the build. `mvn test` runs only
`*Test`, so it stays fast and works with no Docker daemon running. `mvn verify` runs both.

Get the suffix wrong and your test either never runs (`*IT` when you meant `*Test` and only ever
run `mvn test`) or fails on a machine without Docker.

Beyond the suffix, name for what is under test and how: `UserControllerWebMvcTest`,
`UserServiceImplTest`, `UserRepositoryIT`.

### Every `@Test` method gets a Javadoc comment

**Required convention, from Phase 2 onward.** Every test method carries a short Javadoc block
saying what scenario it exercises and what outcome it expects — in plain language, describing the
*situation*, not restating the method name.

```java
/**
 * The Step 8 regression guard. Verifies that an authenticated but unauthorized request —
 * a ROLE_USER token on an ADMIN-only endpoint — resolves to `accessDeniedHandler` and not
 * to the generic catch-all. That exact pairing is what regressed: the 403 handler was
 * correct all along, it was simply never reached, so every role denial came back as a 500.
 */
@Test
void authorizationDeniedNeverReachesTheCatchAll() { ... }
```

Not this:

```java
/** Tests that authorization denied never reaches the catch-all. */   // says nothing new
```

Say what a reader cannot get from the code. Worth a sentence each time:

- **the concrete scenario** — "a ROLE_USER token on an ADMIN-only endpoint", not "an unauthorized
  request"
- **why the test exists**, when it is not obvious — a past defect, a branch that would otherwise
  be uncovered, an assertion that looks redundant but is load-bearing
- **why a choice was made** — a real `Validator` instead of a mock, two violations instead of
  one, a case split into two tests that look like duplicates

The reason is specific to this codebase: several tests here exist because of defects whose
*symptom* was nowhere near their cause. `returns403ForTheAuthorizationDeniedSubclass` looks like
a redundant copy of `returns403` until you know that `@PreAuthorize` throws a subclass by a
different route, and that the subclass is the one that broke. A test whose purpose is invisible
gets "simplified" away by the next person, taking the regression guard with it.

This applies to all future phases, not only the suite that introduced it.

---

## 4. Running tests

```bash
cd photo-app-api/api-parent

mvn test                     # unit tier only - fast, no Docker needed
mvn verify                   # unit + integration tier, plus a JaCoCo report
mvn clean install            # full build; runs the unit tier
```

Single module:

```bash
mvn test -pl services/photo-app-users-service
mvn verify -pl services/photo-app-users-service
```

A module plus everything it depends on — use this when you have changed a library and want to
check its consumers:

```bash
mvn verify -pl services/photo-app-users-service -am
```

A single test class, or a single method:

```bash
mvn test -pl services/photo-app-users-service -Dtest=UserControllerWebMvcTest
mvn test -pl services/photo-app-users-service -Dtest=UserControllerWebMvcTest#userTokenIsForbidden
mvn verify -pl services/photo-app-users-service -Dit.test=UserRepositoryIT
```

Note `-Dtest` targets surefire (`*Test`) and `-Dit.test` targets failsafe (`*IT`).

### Coverage

JaCoCo runs during `verify`. See [§9](#9-viewing-coverage-reports) for where the report lands
and how to read it.

### Do not use `-DskipTests`

Every build in this project's history used it, which is why a suite that asserted almost nothing
went unnoticed for months. If a test is failing, fix it or mark it `@Disabled` **with a written
reason** — do not skip the tier.

### Prerequisites

`*IT` tests need a Docker daemon (Testcontainers starts MySQL). `*Test` does not. There is no CI
pipeline yet, so run the full `mvn verify` locally after each build.

---

## 5. Use `@WithMockPhotoAppUser`, never `@WithMockUser`

**This is the trap.** Spring Security's own `@WithMockUser` installs a
`org.springframework.security.core.userdetails.User` as the principal. This codebase's
`CurrentUserService` does:

```java
if (authentication != null && authentication.getPrincipal() instanceof CustomUserPrincipal principal) {
    return principal;
}
return null;
```

So with `@WithMockUser` the principal is not a `CustomUserPrincipal`, `getCurrentUserId()`
returns `null`, and every ownership-scoped code path takes the wrong branch — **without any error**.
The test passes for the wrong reason, which is worse than failing.

Use the project's annotation instead:

```java
@WithMockPhotoAppUser                                                   // defaults to userId=1, admin, ROLE_ADMIN
@WithMockPhotoAppUser(userId = "2", username = "user1", roles = "ROLE_USER")
@WithMockPhotoAppUser(userId = "")                                      // models a token with no subject claim
```

For tests that go through the HTTP layer and need a real signed token, use `TestJwt` instead —
it exercises `JwtFilter` and `JwtClaimsParser` for real:

```java
.header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(TestJwt.userToken()))
```

Rule of thumb: `TestJwt` when the token itself is part of what you are testing (authorization,
expiry, signatures); `@WithMockPhotoAppUser` when you just need *a* logged-in user so you can
test something else.

---

## 6. The example pattern

A typical `@WebMvcTest`. Note the two imports that make it real — without
`PhotoAppSecuritySliceConfig` the security assertions are meaningless:

```java
package com.photoapp.users.controller;

import com.photoapp.test.support.security.TestJwt;
import com.photoapp.test.support.web.PhotoAppSecuritySliceConfig;
import com.photoapp.users.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;   // Boot 4 package
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@Import(PhotoAppSecuritySliceConfig.class)     // REQUIRED - see §7
class UserControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;           // the layer below is mocked in a slice test

    @Test
    void listingUsersRequiresAdmin() throws Exception {
        mockMvc.perform(get("/users")
                        .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(TestJwt.userToken())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.httpStatus").value(403))
                .andExpect(jsonPath("$.message").value("Forbidden"));
    }

    @Test
    void listingUsersWithoutATokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/users"))
                .andExpect(status().isUnauthorized());
    }
}
```

Two Boot 4 details that will bite if you copy an older example:
- `@WebMvcTest` is in `org.springframework.boot.webmvc.test.autoconfigure`, not
  `org.springframework.boot.test.autoconfigure.web.servlet`.
- Use `@MockitoBean`; `@MockBean` is removed.

A persistence test looks different — extend the container base and use the `IT` suffix:

```java
class UserRepositoryIT extends MySqlContainerSupport {
    @Autowired private UserRepository userRepository;
    // ... real MySQL, schema applied from the database/ module's Liquibase changelogs
}
```

---

## 7. `@Import(PhotoAppSecuritySliceConfig.class)` is not optional

`photo-app-security-lib` has **no auto-configuration**. Applications pick the chain up with
`@ComponentScan("com.photoapp.security")`, and a slice test does not component-scan.

So a bare `@WebMvcTest` gets Spring Boot's **default** security chain, not this project's. Every
authorization assertion then passes against rules that are not the ones shipped. The suite is
green and worthless — which is precisely the failure mode this whole testing initiative exists to
prevent.

`SecuritySliceControlTest` in test-support is the guard. It asserts properties the default chain
does not have: a `USER` token on an `ADMIN` endpoint is **403** (not 401, not 200, and not the
500 it returned before the Step 8 fix), the error body is the project's `ApiErrorDTO`, and
`X-Frame-Options: DENY` plus the CSP are ours.

**If that test fails, fix the configuration — do not weaken the test.** A failure there means
nothing else in the suite can be trusted.

### The two things a `@WebMvcTest` needs before it can test security at all

Learned the hard way in Phase 3. Both are already wired; this is here so the next person does not
spend an afternoon on the same two failures.

**1. `spring-boot-security-test` must be on the classpath.** Boot 4 moved the security
auto-configuration out of `spring-boot-autoconfigure`, and the `@WebMvcTest` slice no longer
lists any of it — *that* artifact is what contributes `ServletWebSecurityAutoConfiguration`
(supplying the `HttpSecurity` bean `SecurityConfiguration#securityFilterChain` takes as a
parameter) and `SecurityMockMvcAutoConfiguration` (putting the chain in front of MockMvc).
Without it the slice has **no security chain whatsoever** — a level below the wrong-chain case
above. It comes in through `photo-app-test-support`, so you get it for free.

**2. Declare a nested `@Configuration` and name it in `@ContextConfiguration`:**

```java
@WebMvcTest
@ContextConfiguration(classes = MyControllerAuthorizationTest.SliceContext.class)
@Import({MyController.class, PhotoAppSecuritySliceConfig.class})
class MyControllerAuthorizationTest {

    @Configuration
    static class SliceContext { }
```

Otherwise Boot walks up the package and finds the real `…Application` class, whose
`@EnableFeignClients` and `@EnableJpaAuditing` demand a Feign infrastructure and a JPA metamodel
that a web slice never builds. Naming it explicitly matters over relying on nested-class
detection: `SpringBootContextLoader` does not detect nested `@Configuration` classes the way the
plain loader does, so `@Nested` inner test classes fall back to the package scan and find the
application class anyway. Declared, it is inherited.

### The three things a Feign test needs before it can test resilience at all

The same failure mode, one layer down. Learned in Phase 4; all three are already wired in
`photo-app-feign-lib`'s pom, and `AbstractFeignClientTest` is the harness to extend.

**1. `spring-boot-data-commons`.** Without it the context does not start at all — every client
fails with `No bean found of type interface feign.codec.Encoder`. `FeignClientsConfiguration`'s
plain encoder is `@ConditionalOnMissingClass(Pageable.class)`, and `spring-data-commons` reaches
every module through `photo-app-commons`, so that bean is *always* skipped here. The replacement
lives in a nested config that needs `DataWebProperties`. Loud, at least — unlike the next two.

**2. `aspectjweaver`.** `@CircuitBreaker` and `@Retry` are Spring AOP annotations. Without this,
Spring never registers `AnnotationAwareAspectJAutoProxyCreator`, the Resilience4j aspects are
never applied, and every resilience assertion passes against a bare Feign call. **Silent.** The
services inherit it from `spring-boot-starter-data-jpa` → `spring-aspects`; a library with no JPA
has to ask.

**3. `WebEnvironment.MOCK`, not `NONE`.** The encoder and decoder are built from the
`HttpMessageConverters` bean, which the web auto-configuration skips when the application type is
`none`.

And one thing to deliberately **not** add: a PATCH-capable Feign transport (`feign-hc5`, okhttp).
Production runs `feign.Client$Default`, and putting a better transport on the test classpath
would make the three `activateOrDeactivate` methods pass here while still failing in production.
See the PATCH item in [plans/backlog.txt](plans/backlog.txt).

**Stub at the HTTP boundary, not at the interface.** `CustomFeignErrorDecoder`, `FeignFallbacks`,
`DownstreamFailurePredicate` and both aspects all sit *below* the client interface, so a Mockito
mock of `UserFeignClient` exercises none of them. WireMock over a real socket is what makes the
assertions mean anything.

**Reset the circuit breakers in `@BeforeEach`.** The registry is a context singleton and the
context is cached across test classes, so a breaker opened by a failure test stays open and fails
the next class's success-path test — in an order that depends on how JUnit sequences the suite.

---

## 8. Conventions worth following

- **Assert the status *and* the body shape.** "A response came back" is not an assertion. The
  Step 8 defect returned a perfectly well-formed 500.
- **Assert log markers where they are the contract** — `ACCESS_DENIED` at WARN,
  `UNHANDLED_EXCEPTION` at ERROR. Several fixes in this codebase are only observable through them.
- **Never assert an exact `timeStamp`.** `ApiErrorDTO.timeStamp` is `LocalDateTime.now()`; assert
  it exists. `json-unit-assertj` is available for shape assertions that ignore volatile fields.
- **Prefer table-driven `@ParameterizedTest`** for the authorization matrix — 32 protected
  endpoints × 3 cases is 96 assertions, and hand-writing them guarantees gaps.
- **Never drive circuit-breaker transitions through production config.** With
  `minimumNumberOfCalls=5` and `waitDurationInOpenState=10s`, one cycle takes tens of seconds.
  Use a test-only instance, or drive the `CircuitBreaker` from its registry.
- **No `Thread.sleep`.** `Clock` is injectable, so mint an already-expired token with a rewound
  clock; use Awaitility for anything genuinely asynchronous.

---

## 9. Viewing coverage reports

### Where it lands

JaCoCo attaches an agent during `test`/`verify` and writes the report in the `verify` phase, so
you need `mvn verify` — `mvn test` alone produces the raw `jacoco.exec` but no HTML.

```bash
cd photo-app-api/api-parent
mvn verify -pl libraries/photo-app-test-support
```

Per module, under its own `target/`:

| Path | What |
|---|---|
| `target/site/jacoco/index.html` | **The report — open this** |
| `target/site/jacoco/jacoco.csv` | Same data, one row per class. Useful for grepping |
| `target/site/jacoco/jacoco.xml` | For tooling |
| `target/jacoco.exec` | Raw execution data the report is generated from |

Open it with your file browser, or:

```bash
start target/site/jacoco/index.html                     # Windows
python -m http.server 8000 --directory target/site/jacoco   # then browse localhost:8000
```

There is **no aggregated report across modules** — see the scoping note below.

### Reading it

Drill from package → class → source. The colours are per line, in the left gutter:

| Colour | Means |
|---|---|
| 🟩 **Green** | Fully covered — every branch on that line was taken |
| 🟥 **Red** | Not executed at all |
| 🟨 **Yellow** | **Partially covered** — the line ran, but only some of its branches did |

**Yellow is the interesting one.** It is where a test exercised an `if` but only ever with the
condition true, or a `switch` with only some cases. Hover the diamond marker in the gutter and
JaCoCo tells you exactly which branches were missed, e.g. *"1 of 2 branches missed"*.

For this codebase that matters more than the percentage: `CustomFeignErrorDecoder` has five
branches, `UserSpecification` has six date-range branches, and `GlobalExceptionHandler` has
eleven handlers. A class can read 100% *line* coverage while half its branches have never run.

The columns in the table view are `Missed Instructions`, `Missed Branches`, `Cxty`, `Lines`,
`Methods`, `Classes`. Sort by **Missed Branches** — that is the column that finds untested logic.

### It is a visibility tool, not a gate

**There is deliberately no coverage threshold, and no build fails on coverage.** This is a
recorded decision (Part 4, decision 6 of [plans/testing-plan.md](plans/testing-plan.md)).

A global percentage target rewards writing tests for getters and DTOs to move a number, which is
the opposite of the point. The purpose here is to **find untested branches and dead code while
you are writing tests** — you write the test you meant to write, then open the report to see what
you missed.

What is actually required is 100% of four specific things, checked by reading the report rather
than by a build rule:

- every `@PreAuthorize` endpoint (Phase 3)
- every `GlobalExceptionHandler` branch (Phase 2)
- every mapper method (Phase 5)
- every Feign fallback (Phase 4)

The report is also good at finding **dead code**: something red that you believe is reachable is
either untested or unreachable, and both are worth knowing.

### Scoping: a report covers only its own module's classes

**This trips people up.** JaCoCo instruments the classes in *that module's* `target/classes`. It
does not report on classes from dependency modules, however hard the tests exercise them.

Verified concretely: `SecuritySliceControlTest` in `photo-app-test-support` drives
`SecurityConfiguration` (from security-lib) and `GlobalExceptionHandler` (from commons) through
eight tests — and neither class appears anywhere in test-support's report. Its packages are only
`com.photoapp.test.support.*`.

The practical consequence, which changes where you put a test if coverage matters to you:

> To see **`GlobalExceptionHandler`** turn green, the tests must live in
> **`photo-app-commons`'s own `src/test`**. Exception-handling tests written inside a *service*
> module will assert correctly and prove the behaviour — but that service's coverage report will
> not show them, because the advice is a commons class.

This lines up with decision 2 in the plan: commons, security-lib and feign-lib get direct suites.
Note commons and security-lib cannot use `photo-app-test-support` (module cycle — see §1), so
their suites use plain JUnit.

### Worked example — Phase 2

Phase 2 is done, so this one can be reproduced rather than imagined:

```bash
cd photo-app-api/api-parent
mvn verify -pl libraries/photo-app-commons
start libraries/photo-app-commons/target/site/jacoco/index.html
```

Drill into `com.photoapp.commons.exception` → `GlobalExceptionHandler`. All **eleven** handler
methods are green, and so is `buildResponse` — the shared body builder, worth checking separately
because partial coverage there would mean a status or body path never ran:

```
GlobalExceptionHandler   0 of 293 instructions missed   0 of 2 branches missed
                         72/72 lines   18/18 methods   0 missed complexity
```

Handlers #1–#7 are the original set (#6 `accessDeniedHandler` being the Step 8 fix); #8–#11
(`typeMismatchHandler`, `noResourceFoundHandler`, `messageNotReadableHandler`,
`methodNotSupportedHandler`) were added *by* Phase 2 after the Phase 1 control test showed
mistyped URLs returning 500.

**The report earned its place here.** The suite passed on its first full run, and JaCoCo still
showed one method uncovered: `lambda$constraintViolationHandler$1`, the `"; "` that joins
multiple violations. The test payload had a single constrained field, so `reduce` never called
the join at all — an untested path in a green suite. That is the failure mode this section
exists to catch, and it is invisible without opening the report.

It remains a **double-check on top of the assertions, not a substitute for them.** Green only proves
a line executed — it says nothing about whether you asserted the right status or the right
`ApiErrorDTO` shape. A handler can be fully green and still be returning 500 where it should
return 403, which is precisely how the Step 8 defect survived. Read the report to find what you
*forgot* to test; trust the assertions for whether the behaviour is right.

## See also

- [plans/testing-plan.md](plans/testing-plan.md) — the phased plan, full inventory and progress
- [../api-parent/libraries/photo-app-test-support/README.md](../api-parent/libraries/photo-app-test-support/README.md) — every fixture and why it exists
- [ARCHITECTURE.md](ARCHITECTURE.md) — the security model the authorization tests assert
- [DATABASE.md](DATABASE.md) — the Liquibase changelogs that build the test schema
- [plans/backlog.txt](plans/backlog.txt) — open defects, several found by tests
