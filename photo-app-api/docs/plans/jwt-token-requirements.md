# JWT token lifecycle — requirements

**Status:** requirements gathering. Nothing here is implemented, and nothing here should be
implemented from this document alone. Written 2026-08-07.

**Purpose.** The 2026-08-07 expired-token defect (`backlog.txt`) was a one-line fix, but finding
it surfaced a wider question this project has never answered: what *should* happen across a
token's whole life? This gathers the questions, records what the system does today as measured
fact, and separates the parts worth patching now from the parts that need a real authorization
server.

**This is not a plan to patch `JwtFilter` seven more times.** `backlog.txt` already records the
platform decision to adopt an OIDC provider (AWS Cognito) for real projects while designing
against the protocol rather than the vendor, and states plainly that
`photo-app-authorization-service` stays as it is because its value is as a learning exercise.
This document feeds *that* decision. §9 is the part to read if you only read one section.

---

## 1. Today's behaviour, measured

Measured against the running stack on 2026-08-07, not inferred from the code. Every row was
produced by a curl against `localhost:8080`.

| Property | Measured value |
|---|---|
| Access token lifetime | **86 400 s** (24 h), `expiresIn` on the login response |
| Refresh token lifetime | **2 592 000 s** (30 days), `REFRESH_TOKEN_VALIDITY` |
| Clock skew tolerance | **0 ms** — jjwt reports `Allowed clock skew: 0 milliseconds` |
| Refresh token rotation | **None.** Refresh returns the *same* token; the original still worked after five refreshes |
| Concurrent refresh | 5 parallel refreshes on one token → **5 × HTTP 200** |
| Access tokens from concurrent refresh | All five were **byte-identical** — `iat`/`exp` are second-granularity |
| Revocation, same instance | Immediate: revoke → 200, next refresh → **401** |
| Access token after revoke | **Still valid** — `GET /users/101` returned 200 with a pre-revoke access token |
| Refresh store | `ConcurrentHashMap` in `TokenHandlerServiceImpl`, per-instance, in-memory |
| Survives a restart? | **No.** Token worked → restarted `photo-app-authorization-service` → same token returned **401** |
| Expired token on `permitAll` | 200 (fixed 2026-08-07; was 401) |
| Expired token on protected path | 401, via `authorizeHttpRequests` |

Two of these are worse than they look:

**Every deploy logs out every user.** The refresh store is an in-memory map, so a rolling
restart silently invalidates every outstanding refresh token. Measured directly. There is no
warning, no error, and no log line saying so — users simply find themselves signed out.

**More than one replica is already incorrect.** Refresh tokens are minted on whichever instance
served the login and are unknown to every other instance. The stack runs one replica today, so
this is latent, not live — but it makes horizontal scaling of the authorization service a
correctness change, not a capacity change.

---

## 2. Token expiration mid-session

**The question.** A token expires between two requests while the user is actively working. Do we
refresh silently, or make them log in again?

24 h is long enough that this is uncommon today, but it is exactly the case that produced the
2026-08-07 defect, and it is guaranteed the moment the access-token lifetime drops to something
defensible (5–15 minutes is typical, and 24 h is not).

**Silent auto-refresh** — the client detects expiry, refreshes, retries. Good UX; the user never
sees it. Costs: the refresh token becomes the real credential and lives 30 days, so its leakage
matters far more than an access token's; and "silent" means a compromised session renews
indefinitely without anyone noticing.

**Force re-login** — simple, auditable, and bounds session length absolutely. Unacceptable UX at
a 15-minute access-token lifetime, which pushes lifetimes back up, which weakens the thing the
short lifetime was for.

**The real answer is neither, it is both with a bound.** Silent refresh within an absolute
session cap (say 8–12 h), after which re-authentication is required regardless of activity.
Also worth deciding: idle timeout separate from absolute timeout.

**To decide:** access-token lifetime; whether an absolute session cap exists and what it is;
whether an idle timeout exists; whether "sensitive" operations (role changes, deletes) demand
recent authentication regardless.

---

## 3. Concurrent requests and the thundering-herd refresh

**The question.** A page fires six requests at once. All six get a 401. Do all six now try to
refresh?

**Measured today: yes, and today it happens to be harmless.** Five parallel refreshes all
returned 200. Nothing breaks because there is no rotation — the token is not consumed, so
concurrent use is not a conflict.

**That safety is accidental and disappears the moment rotation is added** (§5). With rotation,
five concurrent refreshes on one token means one wins and four present a token that has just
been invalidated. Under a reuse-detection policy those four look exactly like a replay attack,
and the correct response to replay is to revoke the whole family — so the user gets logged out
for the crime of loading a page.

**This is the strongest argument for treating rotation and concurrency as one decision, not two.**

Options, none mutually exclusive:

- **Client-side single-flight.** One refresh promise; concurrent callers await it and retry with
  the result. Standard, and the right default. Requires a disciplined client.
- **Server-side grace window.** An old token stays valid for a few seconds after rotation, so
  in-flight requests using it still succeed. Forgiving; slightly widens the replay window.
- **Proactive refresh (§4)** sidesteps most of it — nothing stampedes if the token was renewed
  before anything got a 401.

Note the byte-identical-token measurement: `iat`/`exp` have second resolution, so tokens minted
in the same second are indistinguishable. Harmless today, but it means a token cannot be used as
a unique identifier for auditing or reuse detection without adding a `jti` claim.

**To decide:** where single-flight lives (client, gateway, or both); whether a grace window
exists and how long; whether tokens get a `jti`.

---

## 4. Proactive vs reactive client refresh

**Reactive** — refresh only after a 401. Simple, no clock assumptions, no wasted calls. But every
expiry costs a user-visible round trip, it is the pattern that stampedes (§3), and it requires
the client to distinguish "401 because expired" from "401 because revoked" from "403 because
forbidden" — which today it cannot, because all of them return the same opaque body.

**Proactive** — refresh when the token is within a buffer of expiry (e.g. 60 s), using `exp`.
No user-visible failure, naturally serialised, no stampede. Costs: relies on client clock
accuracy (see §6), needs a timer or an interceptor checking every request, and refreshes tokens
that would never have been used.

**Recommendation for this system: proactive, with reactive as a fallback.** Two reasons specific
to here. First, this is a microservice system behind a gateway — a 401 may be produced by the
gateway, by a downstream service, or by a Feign call between services, and "retry after refresh"
is materially harder to get right across those layers than "do not let it expire". Second, the
inter-service callers are not browsers; `FeignAuthInterceptor` forwards the inbound header, and
a retry-on-401 loop across a service hop is a distributed-systems problem, not an HTTP one.

**To decide:** buffer size; whether server-to-server callers use a different mechanism entirely
(client credentials rather than a forwarded user token — arguably the right answer and a
separate discussion); how a client distinguishes expiry from revocation from authorization
failure, which today it cannot.

---

## 5. Refresh token rotation

**Measured today: no rotation.** One refresh token, valid 30 days, reusable without limit. If it
leaks, the attacker has 30 days of renewable access and there is no way to detect the theft —
legitimate user and attacker use the identical token, and nothing distinguishes them.

**Rotation** issues a new refresh token on every use and invalidates the old one. The benefit is
not only the shorter window; it is **reuse detection**. If an already-rotated token is presented,
either it leaked or something is badly out of sync — and the standard response is to revoke the
entire token family, ending both sessions. That is the only mechanism in this whole document
that can *detect* a stolen refresh token rather than merely limit its usefulness.

Costs: the concurrency problem in §3 becomes real; storage must track families and rotation
state rather than a flat token → user map; and a network failure between "server rotated" and
"client stored the new token" strands the client holding a dead token. The grace window exists
to cover exactly that.

**Recommendation: rotation with reuse detection, a short grace window, and single-flight on the
client — adopted together or not at all.** Rotation without the other two is worse than no
rotation, because it converts an invisible risk into visible logouts.

**To decide:** grace window length; family-revocation policy on reuse (revoke family vs. log and
allow); whether rotation resets the 30-day clock (sliding) or preserves the original expiry
(absolute) — sliding means an active session never has to re-authenticate, which interacts
directly with §2's absolute cap.

---

## 6. Clock skew tolerance

**Measured today: zero.** jjwt reports `Allowed clock skew: 0 milliseconds`. A token is rejected
the instant `exp` passes, with no tolerance for drift between the issuing service, the gateway,
and the verifying service.

All services currently run in one Docker network on one host clock, so drift is effectively nil
and this has never bitten. It stops being true on ECS across availability zones, and NTP drift of
a second or two between hosts is entirely ordinary.

**Recommendation: allow 30–60 s.** This is what `setAllowedClockSkewSeconds` exists for and it is
standard practice. The security cost is precisely bounded — a token is honoured for up to 60 s
past its stated expiry — which is negligible against a 24 h (or even 15 min) lifetime, and far
cheaper than sporadic unexplained 401s that reproduce on no developer's machine.

Note this interacts with §4: a proactive client refreshing on a 60 s buffer while the server
allows 60 s of skew is comfortably safe, whereas a 5 s buffer with 0 skew is not.

**To decide:** the value; whether it applies to `nbf` and `iat` as well as `exp`; whether NTP
sync becomes an explicit infrastructure requirement with monitoring.

---

## 7. Revocation propagation and the storage backend

> **Only relevant if a custom authorization server is built instead of adopting Cognito.**
> Cognito handles token storage, rotation, revocation and propagation natively. If the Cognito
> migration recorded in `backlog.txt` goes ahead, **none of the backends below need to be built
> at all** — this section becomes a description of what was avoided.

### The propagation question

**Measured today:** revocation is immediate and total *on one instance*, because the store is one
map in one JVM. Revoke → next refresh 401, same second.

**And meaningless across instances.** A second replica would not know. Two measured consequences:

- A restart wipes every refresh token — verified: token worked, service restarted, same token
  401'd.
- Revocation on replica A leaves replica B happily refreshing.

**Separately, and by design: revoking a refresh token does not invalidate access tokens already
issued.** Verified — a pre-revoke access token still returned 200 afterwards. `TokenHandlerService`
documents this explicitly. With a 24 h access token that means revocation takes up to 24 h to
fully take effect, which is not a revocation story anyone would accept. Shortening the access
token is the fix; a denylist checked on every request is the alternative and it reintroduces the
per-request state lookup JWTs exist to avoid.

**To decide:** the target — "revoked within N seconds everywhere" — because that number
determines everything else. If N must be near-zero, stateless JWT verification is the wrong
architecture and a denylist or short-lived tokens with introspection is required.

### Backend options

| | Redis | MySQL table | DynamoDB |
|---|---|---|---|
| TTL | Native, matches refresh expiry exactly | None — needs a scheduled cleanup job | Native TTL |
| Revocation lookup | Sub-millisecond | Indexed lookup, fine at this scale | Single-digit ms |
| New infrastructure | Yes — another service to run, secure and back up | **No** — MySQL is already here | Yes, but managed |
| Shared across replicas | Yes | Yes | Yes |
| Durability | Configurable; default is not durable | Durable, backed up with everything else | Durable |
| Fit | The conventional choice | Simplest possible step from today | Only if staying on AWS |

**Redis** is what this is normally built on: native TTL means expired tokens evict themselves,
and lookups are fast enough to check on every request if a denylist is ever needed. The cost is a
new stateful component in a stack that already runs MySQL, RabbitMQ, Elasticsearch, Zipkin and
Eureka.

**A MySQL table** is the smallest honest improvement over today and would fix the two measured
defects — restart amnesia and per-instance isolation — with no new infrastructure, using the
Liquibase pipeline that already exists. It needs a scheduled job to delete expired rows, which is
real but unglamorous work. At this system's scale the performance difference is not the deciding
factor; operational simplicity is.

**DynamoDB** makes sense only if the AWS deployment in `dockerization-plan.md` proceeds and the
authorization service stays custom — which is precisely the combination the Cognito decision says
will not happen.

**Recommendation if a custom server is genuinely built: MySQL first.** It removes both measured
defects at the lowest operational cost, and moving to Redis later is a repository-interface swap
if the store is behind one from the start. **But the more likely correct answer is that this
question never needs answering** — see §9.

**To decide:** the propagation target N (above) — everything else follows from it.

---

## 8. What today's fix did and did not address

The 2026-08-07 change was deliberately narrow: `JwtFilter` no longer short-circuits the chain on
`ExpiredJwtException`, so `permitAll` paths are reachable with a stale token while protected paths
still return 401 through the normal authorization mechanism.

It fixed a **filter-ordering bug**. It changed nothing about lifetimes, rotation, storage,
revocation propagation or clock skew. Every question in §2–§7 was open before it and remains open
after it.

---

## 9. Patch now vs. needs the real system

The most important section. Not everything here is worth doing to a service whose stated value is
that it was hand-built as a learning exercise.

### Reasonable to patch now

| Item | Why it is safe to do here |
|---|---|
| **Clock skew 30–60 s** (§6) | One config value on the parser. Self-contained, no design consequences, prevents a class of bug that will appear on ECS and be miserable to diagnose. |
| **Shorten the access token from 24 h** (§2) | One property. Requires §3/§4 client behaviour to be usable, so it is only *half* safe — but 24 h is indefensible and the current value should at least be a deliberate decision rather than an inherited default. |
| **Refresh store → MySQL table** (§7) | No new infrastructure, uses the existing Liquibase pipeline, and fixes two *measured* defects: restart amnesia and per-instance isolation. The largest correctness gain per unit of risk in this document. |
| **A `jti` claim** (§3) | Additive. Costs nothing, and every auditing or reuse-detection story later needs it. |
| **Distinguishable 401 reasons** (§4) | Expired vs. revoked vs. never-valid are currently one opaque response. A client cannot implement *any* sane refresh strategy without telling them apart. |

### Genuinely needs the real authorization server

| Item | Why patching it here is a mistake |
|---|---|
| **Rotation with reuse detection** (§5) | Only correct alongside single-flight and a grace window, and requires family tracking. Half-built rotation is worse than none — it converts an invisible risk into user-visible logouts. |
| **Near-real-time revocation** (§7) | Either a denylist checked on every request (throwing away the stateless property JWTs are chosen for) or short-lived tokens with introspection. Both are architecture, not a patch. |
| **Absolute session caps and step-up auth** (§2) | Needs session state distinct from token state — a concept this service does not have. |
| **Server-to-server credentials** (§4) | Forwarding an end-user token between services is the wrong model. Fixing it means client-credentials flows, which means an authorization server. |
| **Key rotation / JWKS** | Not in the scope above, but adjacent: a shared HMAC secret across every service cannot be rotated without simultaneous redeployment. Asymmetric signing with a JWKS endpoint is the answer, and that *is* an authorization server. |

### The framing that matters

`backlog.txt` already records the decision: adopt an OIDC provider, design against the protocol
rather than the vendor, and leave `photo-app-authorization-service` as the learning artefact it
is. Read against that, **the right-hand column above is not a to-do list — it is the argument for
the decision that has already been taken.** Every row is something Cognito, Keycloak or Spring
Authorization Server provides having had years of dedicated security review.

The left-hand column is worth doing anyway: it is cheap, it removes measured defects, and it
makes the current system honest about what it does. Nothing in the right-hand column should be
started without first answering whether the custom authorization server is being kept at all.

---

## See also

- [backlog.txt](backlog.txt) — the 2026-08-07 expired-token defect with before/after evidence;
  the OIDC platform decision; the PKCE simplification note
- [testing-plan.md](testing-plan.md) — Phase 3, which surfaced the defect
- [../TESTING.md](../TESTING.md) — how the authorization suites are wired
