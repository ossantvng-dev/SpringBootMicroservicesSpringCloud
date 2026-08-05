# Testing

How testing works in this project: where tests live, what to name them, how to run them, and the
one non-obvious trap that will silently make your tests meaningless.

This is the **living reference**. For the phased plan, the full inventory of what needs covering,
and current progress, see [plans/testing-plan.md](plans/testing-plan.md) — that document is the
planning record, this one is the day-to-day guide.

> **Current state:** Phase 1 (infrastructure) is complete. Phases 2–8 are outstanding, so most
> modules have no behavioural tests yet. If you are adding the first test to a module, this
> document tells you where to put it; the plan tells you what is worth writing.

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
branches, `UserSpecification` has six date-range branches, and `GlobalExceptionHandler` has seven
handlers. A class can read 100% *line* coverage while half its branches have never run.

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

After writing the exception-handler tests:

```bash
cd photo-app-api/api-parent
mvn verify -pl libraries/photo-app-commons
start libraries/photo-app-commons/target/site/jacoco/index.html
```

Drill into `com.photoapp.commons.exception` → `GlobalExceptionHandler` and confirm **all seven
handler methods are green**:

| # | Handler | Expect |
|---|---|---|
| 1 | `applicationExceptionHandler` | green |
| 2 | `validationExceptionHandler` | green |
| 3 | `constraintViolationHandler` | green |
| 4 | `dataAccessExceptionHandler` | green |
| 5 | `optimisticLockExceptionHandler` | green |
| 6 | `accessDeniedHandler` | green — **the Step 8 fix** |
| 7 | `genericExceptionHandler` | green |

Then check `buildResponse` for **yellow**: it is shared by all seven, so partial branch coverage
there means a status or body path never ran.

This is a **double-check on top of the assertions, not a substitute for them.** Green only proves
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
