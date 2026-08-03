# Running the system

Three supported modes. Pick by what you're doing:

| Mode | Use it when | Setup cost |
|---|---|---|
| [A. Fully Dockerized](#a-fully-dockerized-recommended-default) | You want the system running | One command |
| [B. Native (IntelliJ)](#b-native-intellij) | You're debugging across several services | Env vars + 8 run configs |
| [C. Hybrid](#c-hybrid) | You're debugging one or two services | Env vars + a few run configs |

All three share the same infrastructure containers and the same `dev` profile. What changes is
which of the eight Spring Boot apps run in Docker versus on your machine.

> **Prerequisite for all modes:** `.env` must exist at `photo-app-api/.env`. Copy `.env.example`
> and fill it in. It is gitignored and must never be committed.
> ```
> cd photo-app-api
> cp .env.example .env
> ```

---

## A. Fully Dockerized (recommended default)

```bash
cd photo-app-api
bash tools/local/stack.sh up
```

That's it. The script runs `docker compose up -d`, waits for the dependency graph to settle,
then removes the four one-shot init jobs that have finished successfully (`photo-app-liquibase`,
`photo-app-elk-certs`, `photo-app-elk-users`, `photo-app-logs-init`). Compose has no declarative
auto-remove, which is why this wrapper exists.

First run takes several minutes — it builds eight images and generates ELK certificates.
Subsequent runs start in well under a minute.

### What becomes available

| URL | What | Credentials |
|---|---|---|
| http://localhost:8080 | API Gateway — the only way in to the business APIs | JWT via `POST /auth/login` |
| http://localhost:8080/swagger-ui.html | **Aggregated Swagger UI** — all five services in one dropdown | none (anonymous) |
| http://localhost:8761 | Eureka dashboard | none |
| http://localhost:9411 | Zipkin — trace search | none |
| http://localhost:15672 | RabbitMQ management | `RABBITMQ_USER` / `RABBITMQ_PASSWORD` from `.env` |
| http://localhost:5601 | Kibana — log search | `elastic` / `ELASTIC_PASSWORD` from `.env` |
| localhost:3306 | MySQL | `MYSQL_USER` / `MYSQL_PASSWORD` from `.env` |

The five business services (8081–8085) and the Config Server (8888) are **not** published to the
host. They are reachable only from inside the `photo-app-net` network — by design. Reach them
through the gateway, or with `docker exec` for debugging.

Kibana needs a one-time Data View before Discover shows anything — see
[OBSERVABILITY.md](OBSERVABILITY.md).

### Checking health

```bash
docker compose ps                                  # all 14 long-running containers
curl http://localhost:8080/actuator/health          # gateway
curl -s http://localhost:8080/v3/api-docs/swagger-config   # the Swagger dropdown contents
```

### Tearing down — the difference that matters

```bash
bash tools/local/stack.sh down       # stops containers, KEEPS all volumes
```

Use this one. Your MySQL data, Elasticsearch indices, generated certificates, Logstash read
position and Maven cache all survive. Next `up` is fast and everything is where you left it.

```bash
bash tools/local/stack.sh down -v    # DESTRUCTIVE: deletes every volume
```

This prompts for confirmation because it is not reversible. It destroys:

- **`photo-app-mysql-data`** — the entire `photo_app` database. All seed data and anything you
  created is gone. Liquibase re-runs from scratch on next `up`, restoring the seed but not your
  own rows.
- **`photo-app-es-data`** — every log document indexed so far, *and* the Kibana Data View. You
  must recreate the Data View manually afterwards.
- **`photo-app-certs`** — the ELK CA and certificates. Regenerated on next `up`.
- **`photo-app-logs`**, **`photo-app-logstash-data`** — log files and Logstash's read position.
- **`photo-app-maven-cache`** — the next image build re-downloads every dependency.

Only use `-v` when you actually want a clean slate.

---

## B. Native (IntelliJ)

Infrastructure in Docker, all eight Spring Boot apps from IntelliJ.

### 1. Start infrastructure only

```bash
cd photo-app-api
docker compose up -d photo-app-mysql photo-app-liquibase photo-app-rabbitmq photo-app-zipkin elasticsearch kibana logstash
```

Note the ELK service names are `elasticsearch`, `kibana`, `logstash` — they have no
`photo-app-` prefix, unlike everything else in the file. `photo-app-elk-certs` and
`photo-app-elk-users` start automatically as dependencies of `elasticsearch`; you don't list
them. `photo-app-liquibase` is included because the schema has to exist before any service
starts — it exits 0 once migrations are applied.

Tidy up the finished one-shot jobs when they're done:

```bash
bash tools/local/stack.sh clean
```

### 2. Set the environment variables

Every app reads some of these. They must **never** be hardcoded in a properties file and never
committed.

| Variable | Needed by | What it is |
|---|---|---|
| `CONFIG_SERVER_ADMIN_USER` | all 8 | Config Server HTTP Basic username |
| `CONFIG_SERVER_ADMIN_PASSWORD` | all 8 | Config Server HTTP Basic password |
| `KEYSTORE_PASSWORD` | config-server | PKCS#12 keystore password for property encryption |
| `GIT_USERNAME` | config-server | GitHub username for the private config repo |
| `GIT_TOKEN` | config-server | Fine-grained read-only PAT, scoped to the config repo only |
| `RABBITMQ_USER` | config-server | RabbitMQ username (`guest` locally) |
| `RABBITMQ_PASSWORD` | config-server | RabbitMQ password (`guest` locally) |

Two ways to supply them, and the choice matters:

**Windows system environment variables (recommended).** Set them once under
*System Properties → Environment Variables*, then **restart IntelliJ completely**. IntelliJ
snapshots the environment at launch; a running instance will not see variables added after it
started, and this is the single most common cause of a `Could not resolve placeholder
'CONFIG_SERVER_ADMIN_USER'` failure on an otherwise correct setup.

**Per Run Configuration.** *Run → Edit Configurations → Environment variables*. More explicit,
but you repeat it for all eight configurations and it lives in `.run/` — which is gitignored
precisely because those files would otherwise carry `KEYSTORE_PASSWORD`,
`CONFIG_SERVER_ADMIN_PASSWORD`, `GIT_TOKEN` and `RABBITMQ_PASSWORD` into git.

Verify before launching:

```bash
echo $CONFIG_SERVER_ADMIN_USER    # Git Bash
$env:CONFIG_SERVER_ADMIN_USER     # PowerShell
```

### 3. Start the apps in dependency order

Order is not optional. Each step waits for the previous one to be up.

| # | Module | Port | Ready when |
|---|---|---|---|
| 1 | `photo-app-configuration-server` | 8888 | `curl -u $CONFIG_SERVER_ADMIN_USER:$CONFIG_SERVER_ADMIN_PASSWORD http://localhost:8888/actuator/health` returns UP |
| 2 | `photo-app-discovery-service` | 8761 | http://localhost:8761 loads |
| 3 | `photo-app-api-gateway` | 8080 | Registered in the Eureka dashboard |
| 4 | `photo-app-authorization-service` | 8085 | Registered |
| 5 | `photo-app-users-service` | 8081 | Registered |
| 6 | `photo-app-accounts-service` | 8082 | Registered |
| 7 | `photo-app-albums-service` | 8083 | Registered |
| 8 | `photo-app-photos-service` | 8084 | Registered |

Steps 4–8 are independent of each other and can start in parallel. Steps 1–3 cannot, and the
failure mode when you get it wrong is worth knowing:

- The packaged import is `optional:configserver:...`, so a client whose Config Server is down
  does **not** fail fast — it boots on with no external configuration and then dies later on an
  unresolvable placeholder such as `${photoapp.jwt.secret}`. The stack trace points at the
  placeholder, not at the real cause. (In Docker this is cleaner: compose sets
  `SPRING_CLOUD_CONFIG_FAIL_FAST=true`, so it fails immediately and says so.) If a service dies
  on a missing property, check the Config Server first.
- Eureka must exist before the gateway can resolve its `lb://` route URIs.

No environment overrides are needed beyond the table above. The packaged
`application.properties` files default to `localhost` for the Config Server, and the `dev`
profile in the config repo points at `localhost` for MySQL, RabbitMQ and Zipkin — which is
exactly where the containers publish.

### 4. Per-service Swagger UI

Running natively, each service exposes its own docs directly:

| Service | Swagger UI |
|---|---|
| authorization | http://localhost:8085/swagger-ui.html |
| users | http://localhost:8081/swagger-ui.html |
| accounts | http://localhost:8082/swagger-ui.html |
| albums | http://localhost:8083/swagger-ui.html |
| photos | http://localhost:8084/swagger-ui.html |

The aggregated view at http://localhost:8080/swagger-ui.html works too, since the gateway
resolves each service through Eureka either way.

### Known limitation: native logs do not reach Kibana

Logstash tails the `photo-app-logs` Docker volume. A natively-run service writes to
`api-parent/services/<service-name>/logs/` on your host filesystem instead, and that path is not
mounted into Logstash. So in modes B and C:

- **Zipkin tracing works** — natively-run services push spans to `localhost:9411` like any other client.
- **Kibana will not show logs from natively-run services.** Read those from the IntelliJ console
  or the local log file. Logs from services still running in Docker keep flowing to Kibana normally.

---

## C. Hybrid

The practical everyday mode: run the one or two services you're actively working on from
IntelliJ, leave the rest in Docker.

### 1. Same infrastructure command as mode B

```bash
cd photo-app-api
docker compose up -d photo-app-mysql photo-app-liquibase photo-app-rabbitmq photo-app-zipkin elasticsearch kibana logstash
```

### 2. Start the Docker-hosted apps you are *not* debugging

```bash
# always needed
docker compose up -d photo-app-config-server photo-app-discovery-service

# then whichever business services you don't want to debug
docker compose up -d photo-app-api-gateway photo-app-accounts-service photo-app-albums-service photo-app-photos-service
```

### 3. Run the rest from IntelliJ

Same environment variables as mode B, same startup ordering rules. Just skip the ones already
running in Docker.

### Why this works: hostname resolution

The two halves resolve names differently, and both are already handled:

| Runs where | Reaches MySQL / RabbitMQ / Zipkin / Config Server at |
|---|---|
| Natively (IntelliJ) | `localhost` — the ports the containers publish |
| In Docker | `photo-app-mysql`, `photo-app-rabbitmq`, `photo-app-zipkin`, `photo-app-config-server` |

This is not something you configure per run. It falls out of the existing two-layer strategy:

- The **`dev` profile in the config repo** uses `localhost` everywhere. That is what a natively
  run app picks up, unmodified.
- **`docker-compose.yml` overrides those with OS environment variables**
  (`SPRING_DATASOURCE_URL`, `SPRING_RABBITMQ_HOST`, `MANAGEMENT_ZIPKIN_TRACING_ENDPOINT`,
  `CONFIG_SERVER_URL`, `EUREKA_CLIENT_SERVICEURL_DEFAULTZONE`), which outrank config-server
  values. That is what a containerized app picks up.

So the same jar, the same config repo and the same profile work in both places. Nothing needs
switching when you move a service from Docker to IntelliJ or back.

### The one thing to watch: Eureka registration

Containers register under their **container hostname** (`EUREKA_INSTANCE_HOSTNAME`, with
`prefer-ip-address=false`). A natively-run service registers under your machine's hostname.

Both are resolvable from the host, so the gateway running natively reaches everything. But a
service running **inside Docker** cannot resolve your host machine's hostname, so it cannot call
a natively-run service.

In practice this only bites on one path — the `accounts → users` Feign call. If you run
`users-service` natively while `accounts-service` stays in Docker, that call fails. Run both
natively, or both in Docker. Every other combination is fine, because all other traffic flows
host → gateway → service.

### Tearing down a hybrid session

Stop the IntelliJ processes, then:

```bash
bash tools/local/stack.sh down    # keeps volumes — see mode A for what -v destroys
```
