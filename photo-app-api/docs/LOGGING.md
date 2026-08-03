# Logging

How log levels are set, why they differ between environments, and what the rules are.

For *reading* the logs once they exist — Kibana setup, ready-made queries — see
[OBSERVABILITY.md](OBSERVABILITY.md).

---

## The principle

**INFO is the default floor per package in production. DEBUG and TRACE are for temporary
investigation, not for permanent configuration.**

The reasoning is not stylistic. Every log line in this stack is written as JSON to disk, tailed
by Logstash, shipped over TLS to Elasticsearch, and indexed. A verbose logger does not cost one
write, it costs a write plus transport plus indexing plus storage, on every request, forever. It
also buries the lines that matter.

The most expensive logger in this project is `org.hibernate.orm.jdbc.bind` at TRACE: it emits
every JDBC parameter of every statement. On a paged list endpoint that is thousands of lines per
request. It was identified as a measurable contributor during the latency investigation recorded
in [plans/backlog.txt](plans/backlog.txt), and was lowered to WARN in every `dev` profile as a
result.

When you *do* need DEBUG or TRACE, turn it on, learn the thing, turn it off. Two ways that do
not involve leaving it in a file:

```bash
# Runtime, no restart, no commit — reverts on next restart
curl -X POST http://localhost:8081/actuator/loggers/org.hibernate.SQL \
     -H 'Content-Type: application/json' -d '{"configuredLevel":"DEBUG"}'
```

Or edit the config repo, push, and `POST /actuator/busrefresh` (ADMIN) to propagate over
RabbitMQ — then revert when finished.

---

## Where levels are configured

Three places, in increasing precedence:

1. **`logback-spring.xml`** in each module — sets the root logger to `INFO` and defines the two
   appenders (`JSON_CONSOLE`, `JSON_FILE`). Structure, not policy. It is identical across all
   eight components.
2. **`<service>-<profile>.properties`** in `photo-app-configuration-repo` — this is where the
   per-package policy actually lives, and where you change it.
3. **OS environment variables / actuator** — overrides for a specific container or a specific
   investigation.

Because levels live in the config repo, changing one means committing **and pushing**: the
Config Server runs `force-pull=true` against `main`, so an unpushed edit is invisible.

---

## Current posture

### Business services — `dev`

Identical across users, accounts, albums, photos and authorization:

```properties
logging.level.com.photoapp.<service>=DEBUG
logging.level.org.hibernate.SQL=debug
logging.level.org.hibernate.orm.jdbc.bind=WARN
logging.level.org.springframework.cloud.openfeign=TRACE
logging.level.org.springframework.cloud.circuitbreaker=TRACE
logging.level.io.github.resilience4j=TRACE
```

`org.hibernate.SQL` at DEBUG is kept on purpose — seeing the statement is what makes a dev log
useful. Its parameter-level counterpart `org.hibernate.orm.jdbc.bind` is held at WARN, which is
the setting that removes the cost without removing the value.

The three Feign / resilience loggers at TRACE are a deliberate deviation from the principle, and
a temporary one: they exist because the `accounts → users` circuit breaker path was under active
investigation. They should come down to DEBUG or INFO once that work closes. Tracked in
[plans/backlog.txt](plans/backlog.txt).

### Business services — `prod`

```properties
logging.level.org.hibernate.SQL=info
logging.level.org.hibernate.orm.jdbc.bind=info
logging.level.org.springframework.cloud.openfeign=info
logging.level.org.springframework.cloud.circuitbreaker=info
logging.level.io.github.resilience4j=info
```

The Hibernate pair was already at INFO. The three Feign / resilience loggers were **added** —
before that they were unset in prod, which meant they inherited the root level by accident
rather than by decision. Naming them makes the intent explicit and stops a future root-level
change from silently switching TRACE-capable loggers on in production.

**All five business services now agree in prod.** `photo-app-authorization-service-prod.properties`
had kept `org.hibernate.SQL=debug` and `org.hibernate.orm.jdbc.bind=trace` — the expensive one,
on the service that handles credentials, so every credential lookup had its bind parameters
written to disk and shipped to Elasticsearch. Both were lowered to `info` to match the others.

### Gateway

```properties
# dev
logging.level.org.springframework.cloud.gateway=DEBUG
logging.level.org.springframework.cloud.gateway.server.mvc.handler=TRACE
# prod
logging.level.org.springframework.cloud.gateway=INFO
```

The `…server.mvc.handler` logger at TRACE in dev is what shows which route matched a request —
worth having while route configuration is still changing.

### Config Server and Discovery Service

Neither sets any `logging.level.*`. Both inherit the root `INFO` from `logback-spring.xml`.
That already satisfies the principle, so nothing was added.

---

## Removed: the dead `BasicBinder` logger

```properties
# deleted from all five *-prod.properties files
logging.level.org.hibernate.type.descriptor.sql.BasicBinder=info   # or =trace
```

`org.hibernate.type.descriptor.sql.BasicBinder` was the parameter-binding logger up to
Hibernate 5. In **Hibernate 7.2.7** — the version this project runs — that class does not exist;
it moved to `org.hibernate.type.descriptor.jdbc`, and the logger you actually want is
`org.hibernate.orm.jdbc.bind`.

So the line named a logger nothing ever writes to. Setting it to `trace` did nothing and setting
it to `info` did nothing. It had already been removed from the `dev` files during the latency
investigation; Step 9 removed the five remaining `prod` copies. Verified absent from the whole
config repo.

This is the general failure mode with logger names: they are strings, and a wrong one fails
**silently**. Nothing warns you that a configured logger does not correspond to any real
category. If a level change appears to have no effect, verify the logger name before assuming
the configuration is not being read — `GET /actuator/loggers` lists the categories a running
service actually knows about.

---

## Log format

Every component emits JSON via `LogstashEncoder`, to console and to a rolling file at
`${LOG_BASE}/${serviceName}/logs/${serviceName}.log` (10 days retained). `LOG_BASE` is
`/var/log/photo-app` in containers — a volume shared by all eight — and defaults to
`api-parent/services` for a native run.

Fields available for querying:

| Field | Source |
|---|---|
| `@timestamp`, `level`, `logger_name`, `thread_name`, `message` | LogstashEncoder defaults |
| `traceId`, `spanId` | Micrometer Tracing, via the SLF4J MDC |
| `service` | custom field, from `spring.application.name` |
| `environment` | custom field, from `spring.profiles.active` |

`traceId` is what makes the logs worth more than the sum of their parts — it is the same id
Zipkin uses, so one identifier moves you between the two. See [ARCHITECTURE.md](ARCHITECTURE.md)
for the correlation mechanism and [OBSERVABILITY.md](OBSERVABILITY.md) for the queries.

### Application log conventions

Some patterns worth knowing because they are queryable:

- `ACCESS_DENIED path=… message=…` at **WARN** — emitted by `GlobalExceptionHandler` when an
  authorization rule rejects a request. Deliberately WARN, not ERROR: a correctly rejected
  request is the system working.
- `AUTH LOGIN …` — authentication events from the authorization service.
- `HTTP <METHOD> <path> - … request received` — controller entry, at INFO for mutations and
  DEBUG for reads.
- `Expired JWT detected …` at WARN, `Invalid JWT …` at ERROR — from `JwtFilter`.

---

## Checklist for changing a level

1. Is this permanent, or an investigation? If it's an investigation, use the actuator endpoint
   and change nothing in git.
2. If permanent — does it hold in prod as well, or only dev? Edit the right file(s).
3. Does the logger name exist? Check `GET /actuator/loggers`.
4. Commit **and push** the config repo.
5. `POST /actuator/busrefresh` (ADMIN) to apply without restarting, or restart the service.

## See also

- [OBSERVABILITY.md](OBSERVABILITY.md) — Kibana Data View setup and ready-made queries
- [ARCHITECTURE.md](ARCHITECTURE.md) — how tracing and logging correlate
- [plans/backlog.txt](plans/backlog.txt) — the latency investigation and open logging items
