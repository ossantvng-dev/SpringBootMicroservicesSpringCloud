# ELK stack

Elasticsearch, Logstash and Kibana, with `xpack.security` **enabled** — which means certificates
and authentication are required, not optional.

## Layout

```
docker/elk/
├── setup/
│   ├── certs.sh              stage 1: generate CA + certificates
│   └── users.sh              stage 2: set built-in user passwords
└── logstash/pipeline/
    └── pipeline.conf         file input → Elasticsearch output
```

## The two-stage bootstrap

Enabling `xpack.security` creates a chicken-and-egg problem: Elasticsearch will not start without
certificates, and you cannot set user passwords without a running Elasticsearch. So bootstrap is
split into two one-shot containers:

| Stage | Container | Runs | Does |
|---|---|---|---|
| 1 | `photo-app-elk-certs` | before Elasticsearch | Generates a CA and node certificates onto the `photo-app-certs` volume. Reuses existing certs if present. |
| 2 | `photo-app-elk-users` | after Elasticsearch is healthy | Sets the `kibana_system` password from `.env` so Kibana can authenticate. |

Both are idempotent, which is why `tools/local/stack.sh` removes them once they exit 0.

They are gated in `docker-compose.yml` with
`depends_on: condition: service_completed_successfully`, so a plain `docker compose up -d` also
produces a correct stack — just with the finished job containers left behind.

## Credentials

All from `.env` (gitignored, never committed):

| Variable | Used by |
|---|---|
| `ELASTIC_PASSWORD` | The `elastic` superuser — Kibana login, Logstash output |
| `KIBANA_SYSTEM_PASSWORD` | The `kibana_system` service account |
| `ELK_VERSION` | Image tag for all three |

**Passwords are set at bootstrap, not reconciled on every start.** Changing `ELASTIC_PASSWORD` in
`.env` after the volume exists does not update the stored password — the old one stays in effect.

## Logstash pipeline

**Input:** tails `/var/log/photo-app/**/logs/*.log` on the shared `photo-app-logs` volume, which
all eight application containers write to. Codec is `json` — the apps already emit structured
JSON via `LogstashEncoder`, so nothing is parsed out of a text line.

**sincedb:** persisted to `/usr/share/logstash/data/sincedb_photoapp` on the
`photo-app-logstash-data` volume. This is load-bearing — the previous `/dev/null` setting made
every restart re-ingest every file from the beginning and duplicate every document in
Elasticsearch.

**Output:** `https://elasticsearch:9200`, authenticated as `elastic`, trusting the CA from stage 1.
Index `photoapp-logs-%{+YYYY.MM.dd}` — daily, which is why the Kibana Data View pattern needs the
wildcard `photoapp-logs-*`.

`XPACK_MONITORING_ENABLED=false`: Logstash's license reader used plain HTTP against an
HTTPS-only cluster and failed on startup.

## Volumes

| Volume | Holds | Lost on `down -v` |
|---|---|---|
| `photo-app-certs` | CA and node certificates | Regenerated automatically |
| `photo-app-es-data` | All indexed logs **and the Kibana Data View** | Data View must be recreated by hand |
| `photo-app-logs` | The raw log files | |
| `photo-app-logstash-data` | sincedb read position | Causes a full re-ingest |

## Container names vs compose service names

These three are the only services whose compose name differs from their container name:

| Compose service | Container |
|---|---|
| `elasticsearch` | `photo-app-elasticsearch` |
| `kibana` | `photo-app-kibana` |
| `logstash` | `photo-app-logstash` |

Use the **service** name with `docker compose up`, the **container** name with `docker exec`.

## See also

- [../../docs/OBSERVABILITY.md](../../docs/OBSERVABILITY.md) — Kibana Data View setup, queries, troubleshooting
- [../../docs/LOGGING.md](../../docs/LOGGING.md) — what gets logged and at what level
