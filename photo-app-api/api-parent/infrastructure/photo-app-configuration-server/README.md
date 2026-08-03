# photo-app-configuration-server

Spring Cloud Config Server. Serves externalized configuration to the other seven components from
a private Git repository.

**Port:** 8888 (not published to the host — reachable only inside `photo-app-net`)

## What it does

Runs the `git` profile against
[`photo-app-configuration-repo`](https://github.com/ossantvng-dev/photo-app-configuration-repo),
`search-paths=config-repo`, `default-label=main`, `clone-on-start=true`, **`force-pull=true`**.

That last setting is the one to remember: the server re-pulls `main` on every request, so a
config change that has been committed but **not pushed does not exist** as far as this server is
concerned.

Also provides `/encrypt` and `/decrypt` (ADMIN only) backed by a PKCS#12 keystore, and
`/actuator/busrefresh` to propagate config changes over RabbitMQ without restarting anything.

## Security

HTTP Basic, one admin account, defined in `SecurityConfiguration`. `/actuator/health` is left
anonymous so Docker's healthcheck works; everything else requires authentication.

This deliberately does **not** use `photo-app-security-lib`. That library is JWT-based and built
for human end-users authenticating through the gateway. This server is consumed by other Spring
services at startup — a different consumer model, which HTTP Basic fits correctly.

## Depends on

| On | Why |
|---|---|
| GitHub (config repo) | Source of all configuration |
| RabbitMQ | Spring Cloud Bus transport for `busrefresh` |
| Zipkin | Span reporting |

**Not** registered with Eureka, on purpose: clients reach it by direct URL through
`spring.config.import`, so registering it would only add a bootstrap chicken-and-egg problem.

Nothing else can start until this is healthy.

## Environment variables

| Variable | Required | Purpose |
|---|---|---|
| `CONFIG_SERVER_ADMIN_USER` | yes | HTTP Basic username it serves |
| `CONFIG_SERVER_ADMIN_PASSWORD` | yes | HTTP Basic password it serves |
| `GIT_USERNAME` | yes | GitHub account for the private config repo |
| `GIT_TOKEN` | yes | Fine-grained read-only PAT, scoped to that repo only |
| `KEYSTORE_PASSWORD` | yes | Password of the `/encrypt` `/decrypt` keystore |
| `RABBITMQ_USER`, `RABBITMQ_PASSWORD` | yes | Bus transport credentials |
| `SPRING_RABBITMQ_HOST` | container | `photo-app-rabbitmq` |
| `MANAGEMENT_ZIPKIN_TRACING_ENDPOINT` | container | Zipkin collector URL |

## The keystore

`encrypt.keyStore.location=classpath:/keystore.p12`, but the file is **never in the image**.
`*.p12` / `*.jks` are gitignored and excluded in `.dockerignore`; `docker-compose.yml`
bind-mounts the file read-only over the classpath location at runtime. It exists on disk and at
runtime, never in git and never in a layer.

## No Swagger UI

Infrastructure, not a business API. springdoc is deliberately not added here.

## Useful commands

```bash
# health (anonymous)
curl http://localhost:8888/actuator/health

# what config a service will receive
curl -u "$CONFIG_SERVER_ADMIN_USER:$CONFIG_SERVER_ADMIN_PASSWORD" \
     http://localhost:8888/photo-app-users-service/dev

# propagate a pushed config change to every service, no restart
curl -X POST -u "$CONFIG_SERVER_ADMIN_USER:$CONFIG_SERVER_ADMIN_PASSWORD" \
     http://localhost:8888/actuator/busrefresh
```

## See also

- [../../../docs/ARCHITECTURE.md](../../../docs/ARCHITECTURE.md) — configuration layering and precedence
- [../../../docs/RUNNING.md](../../../docs/RUNNING.md) — required env vars for native runs
