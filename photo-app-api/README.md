# Photo App API

A photo-sharing backend built as a Spring Boot 4 / Spring Cloud microservices platform on
Java 25. Eight Spring Boot applications — a Config Server, a Eureka registry, an API gateway and
five business services (authorization, users, accounts, albums, photos) — plus MySQL, RabbitMQ,
Zipkin and an authenticated ELK stack, all wired together in a single `docker compose` file. It
is a single Maven reactor with five shared libraries, a Liquibase-owned schema, JWT security
enforced at every hop, and distributed tracing correlated with logs by `traceId`.

## Quick start

```bash
cd photo-app-api
cp .env.example .env          # fill in the blanks — .env is gitignored
bash tools/local/stack.sh up
```

First run builds eight images and generates ELK certificates, so give it a few minutes. After
that:

| | |
|---|---|
| **Try the API** | http://localhost:8080/swagger-ui.html — all five services in one dropdown |
| **Get a token** | `POST /auth/login` with `{"username":"admin","password":"generic"}` |
| **See the registry** | http://localhost:8761 |
| **See a trace** | http://localhost:9411 |
| **See the logs** | http://localhost:5601 — needs a one-time [Data View](docs/OBSERVABILITY.md#2-one-time-kibana-setup-the-data-view) |

Stop it without losing data:

```bash
bash tools/local/stack.sh down          # keeps volumes
```

`down -v` destroys the database, the logs and the certificates. See
[RUNNING.md](docs/RUNNING.md#tearing-down--the-difference-that-matters) for exactly what goes.

## Documentation

| Document | Read it when |
|---|---|
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | You want to understand what the eight components are, how they relate, the security model, and how tracing and logging correlate |
| [RUNNING.md](docs/RUNNING.md) | You want to run it — fully Dockerized, natively from IntelliJ, or a hybrid of the two |
| [DATABASE.md](docs/DATABASE.md) | You need to change the schema, or want to know what the seed data is |
| [LOGGING.md](docs/LOGGING.md) | A log level is wrong, too noisy, or apparently being ignored |
| [OBSERVABILITY.md](docs/OBSERVABILITY.md) | You're debugging and want the Kibana and Zipkin workflow, with copy-pasteable queries |
| [TESTING.md](docs/TESTING.md) | You're writing or running tests — where they go, what to name them, and the `@WithMockUser` trap that silently makes them meaningless |

Background and history, kept as a record rather than as instructions:

- [docs/plans/PLATFORM-VISION.md](docs/plans/PLATFORM-VISION.md) — long-term direction
- [docs/plans/dockerization-plan.md](docs/plans/dockerization-plan.md) — how the current shape was arrived at, and the AWS backlog
- [docs/plans/testing-plan.md](docs/plans/testing-plan.md) — the phased testing plan, inventory and progress
- [docs/plans/backlog.txt](docs/plans/backlog.txt) — open items and recorded decisions

## Modules

Every module has its own README with its port, dependencies and environment variables.

**Infrastructure**

| Module | Port | |
|---|---|---|
| [photo-app-configuration-server](api-parent/infrastructure/photo-app-configuration-server) | 8888 | Config from a private Git repo |
| [photo-app-discovery-service](api-parent/infrastructure/photo-app-discovery-service) | 8761 | Eureka registry |
| [photo-app-api-gateway](api-parent/infrastructure/photo-app-api-gateway) | 8080 | The only public entry point |
| [photo-app-discovery-service-cluster](api-parent/infrastructure/photo-app-discovery-service-cluster) | — | Reference only, not containerized |

**Services** — each links to its Swagger UI

| Module | Port | |
|---|---|---|
| [photo-app-authorization-service](api-parent/services/photo-app-authorization-service) | 8085 | Issues, refreshes and revokes JWTs |
| [photo-app-users-service](api-parent/services/photo-app-users-service) | 8081 | Users, roles, activation |
| [photo-app-accounts-service](api-parent/services/photo-app-accounts-service) | 8082 | Accounts; the one Feign caller |
| [photo-app-albums-service](api-parent/services/photo-app-albums-service) | 8083 | Albums and per-account limits |
| [photo-app-photos-service](api-parent/services/photo-app-photos-service) | 8084 | Photos within albums |

**Libraries**

| Module | |
|---|---|
| [photo-app-commons](api-parent/libraries/photo-app-commons) | DTOs, MapStruct mappers, global exception handler |
| [photo-app-entity-model-lib](api-parent/libraries/photo-app-entity-model-lib) | JPA entities |
| [photo-app-feign-lib](api-parent/libraries/photo-app-feign-lib) | Feign clients, auth interceptor, error decoder |
| [photo-app-security-lib](api-parent/libraries/photo-app-security-lib) | JWT filter and the shared security chain |
| [photo-app-tracing-lib](api-parent/libraries/photo-app-tracing-lib) | Zipkin sender replacement |
| [photo-app-test-support](api-parent/libraries/photo-app-test-support) | Shared test fixtures — **test scope only**, never in a production image |

**Other**

| | |
|---|---|
| [database](database) | Liquibase changelogs — the single source of schema truth |
| [docker/elk](docker/elk) | ELK bootstrap scripts and the Logstash pipeline |

## Building

```bash
cd api-parent
mvn clean install     # runs the unit tier
mvn verify            # unit + Testcontainers-backed integration tier, plus coverage
```

Build from `api-parent`, not the root pom. The root aggregates the `database` module, whose
`liquibase:update` is bound to `process-resources` and would try to reach a live database. The
`Dockerfile` does the same for the same reason.

**Don't reach for `-DskipTests`.** Every build in this project's history used it, which is how a
suite that asserted almost nothing went unnoticed. See [TESTING.md](docs/TESTING.md).

## Configuration lives elsewhere

Runtime configuration is in a separate private repository,
[`photo-app-configuration-repo`](https://github.com/ossantvng-dev/photo-app-configuration-repo).
The Config Server runs with `force-pull=true` against `main`, so **a config change that is
committed but not pushed does not exist** as far as the running system is concerned.

Secrets — keystore password, config-server credentials, Git PAT, RabbitMQ and Elasticsearch
passwords — come from `.env` and OS environment variables. They are never committed, never baked
into an image, and the encryption keystore is bind-mounted at runtime rather than packaged.

## Tech stack

Spring Boot 4.0.6 · Spring Cloud 2025.1.1 · Java 25 · Spring Cloud Gateway (webmvc) ·
Eureka · OpenFeign + Resilience4j · Spring Security + JJWT · MySQL 8.4 · Liquibase 4.29.2 ·
MapStruct 1.6.3 · Micrometer Tracing + Zipkin · Elasticsearch/Logstash/Kibana · springdoc-openapi 3.0.3
