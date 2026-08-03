# photo-app-api-gateway

Spring Cloud Gateway (**servlet / webmvc** stack). The single public entry point — the only
application port published to the host besides the Eureka dashboard.

**Port:** 8080 → http://localhost:8080

## What it does

- Routes `/users/**`, `/accounts/**`, `/albums/**`, `/photos/**`, `/auth/**` to the five business
  services via `lb://SERVICE-ID`, resolved through Eureka.
- Applies the shared security chain: JWT validation, `X-Frame-Options: DENY`, CSP
  `script-src 'self'`.
- Hosts the **aggregated Swagger UI**.

## Swagger UI — the aggregated entry point

**http://localhost:8080/swagger-ui.html**

The gateway has no endpoints of its own; its UI is purely an aggregator with a dropdown of all
five business services. "Try it out" then routes through the gateway exactly like a real client
would, so what you test is what you ship.

The mechanism: five extra routes republish each service's `/v3/api-docs` under a shared
`/api-docs/{service}` namespace using `SetPath`, and `springdoc.swagger-ui.urls[N]` points at
those. Both live in `photo-app-api-gateway-dev.properties` in the config repo, next to the
routes they depend on.

Prod uses `discovery.locator.enabled=true` (dynamic routes by service id) rather than explicit
routes, so aggregation is currently dev-only.

## webmvc, not webflux

Deliberate. `spring-cloud-starter-gateway-server-webmvc` puts the gateway on the same servlet
stack as the five business services, which is what allows **one** `SecurityFilterChain` in
`photo-app-security-lib` to serve all six. The previous reactive configuration had drifted out
of sync with the servlet one and had silently lost frame-options and CSP.

Note the property prefix is `spring.cloud.gateway.server.webmvc.routes`, not the webflux
`spring.cloud.gateway.routes`.

## One non-obvious setting

```properties
spring.cloud.gateway.server.webmvc.function.enabled=false
```

Gateway MVC's `DefaultFunctionConfiguration` registers a **catch-all** router function whenever
`spring-cloud-function-context` is on the classpath — which `spring-cloud-starter-bus-amqp`
drags in — and it defaults to **on** (`matchIfMissing = true`). Every unrouted path, including
Swagger UI's static webjar resources, was being handed to `RoutingFunction` and rejected with a
415 before the resource handler ever saw it. No route here uses `fn://`, so it is pure dead
weight.

Not to be confused with `spring.cloud.function.web.enabled`, which is a no-op in this project.

## Depends on

| On | Why |
|---|---|
| Config Server | Routes, JWT secret, tracing config |
| Discovery Service | Resolving `lb://` URIs — must be healthy first |
| RabbitMQ | Spring Cloud Bus |
| Zipkin | Span reporting |
| `photo-app-security-lib` | The shared security chain |
| `photo-app-tracing-lib` | Zipkin sender replacement |

## Environment variables

| Variable | Required | Purpose |
|---|---|---|
| `CONFIG_SERVER_ADMIN_USER` / `CONFIG_SERVER_ADMIN_PASSWORD` | yes | Config Server auth |
| `CONFIG_SERVER_URL` | container | `http://photo-app-config-server:8888` |
| `EUREKA_CLIENT_SERVICEURL_DEFAULTZONE` | container | Eureka URL |
| `EUREKA_INSTANCE_HOSTNAME` | container | `photo-app-api-gateway` |
| `EUREKA_INSTANCE_PREFER_IP_ADDRESS` | container | `false` — register by hostname |
| `SPRING_RABBITMQ_HOST` | container | `photo-app-rabbitmq` |
| `MANAGEMENT_ZIPKIN_TRACING_ENDPOINT` | container | Zipkin collector URL |

## See also

- [../../../docs/ARCHITECTURE.md](../../../docs/ARCHITECTURE.md) — security model and routing
- [../../../docs/RUNNING.md](../../../docs/RUNNING.md) — what's reachable in each mode
