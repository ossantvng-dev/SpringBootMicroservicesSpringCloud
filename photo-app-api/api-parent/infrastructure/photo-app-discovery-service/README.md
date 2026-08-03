# photo-app-discovery-service

Netflix Eureka server. The service registry the gateway uses to resolve `lb://` route URIs.

**Port:** 8761 — **published** to the host, so the dashboard is reachable at
http://localhost:8761

## What it does

Every component except the Config Server registers here on startup and heartbeats thereafter.
The gateway asks it where `PHOTO-APP-USERS-SERVICE` currently lives; Spring Cloud LoadBalancer
then picks an instance.

## Registration model in Docker

Containers register by **hostname**, not IP:

```yaml
EUREKA_INSTANCE_PREFER_IP_ADDRESS: "false"
EUREKA_INSTANCE_HOSTNAME: photo-app-users-service   # the compose service name
```

The container name *is* a resolvable DNS name on `photo-app-net`, so a hostname registration is
directly dialable by every other container. IP-based registration also works but produces
churny, meaningless addresses in the dashboard.

## Depends on

| On | Why |
|---|---|
| Config Server | Reads its own config from it — must be healthy first |
| Zipkin | Span reporting |

Registers nothing itself.

## Environment variables

| Variable | Required | Purpose |
|---|---|---|
| `CONFIG_SERVER_ADMIN_USER` / `CONFIG_SERVER_ADMIN_PASSWORD` | yes | To read its own config |
| `CONFIG_SERVER_URL` | container | `http://photo-app-config-server:8888` |
| `EUREKA_INSTANCE_HOSTNAME` | container | `photo-app-discovery-service` |
| `MANAGEMENT_ZIPKIN_TRACING_ENDPOINT` | container | Zipkin collector URL |

## Topology

**Single instance.** A peer-aware cluster configuration exists in
[`../photo-app-discovery-service-cluster`](../photo-app-discovery-service-cluster) for
reference, but it is deliberately not containerized. Production topology is an open decision —
see the AWS backlog in [`../../../docs/plans/dockerization-plan.md`](../../../docs/plans/dockerization-plan.md).

## No Swagger UI

Infrastructure, not a business API. The Eureka dashboard at http://localhost:8761 is its UI.

## See also

- [../../../docs/ARCHITECTURE.md](../../../docs/ARCHITECTURE.md) — startup order and why it matters
- [../../../docs/RUNNING.md](../../../docs/RUNNING.md) — the hybrid-mode registration caveat
