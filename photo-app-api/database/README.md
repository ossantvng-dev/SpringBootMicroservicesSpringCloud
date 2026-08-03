# photo-app-database

The single source of truth for the `photo_app` schema. Liquibase changelogs and the Maven plugin
that applies them.

**Not a service.** No port, no `main`, no Spring Boot application. It produces no jar that
anything else depends on — it is run, not linked.

## What it does

Owns every table, constraint and seed row used by the five business services. Applications never
create or alter schema: every profile sets `spring.jpa.hibernate.ddl-auto=none`.

**Liquibase only — never Flyway, never per-service migration directories.**

## Layout

```
database/
├── pom.xml                            liquibase-maven-plugin 4.29.2 + MySQL driver
├── docs/
│   ├── create_db_and_user.sql
│   └── drop_tables.sql
└── src/main/resources/db/changelog/
    ├── changelog-master.xml           the include list — order is execution order
    ├── users/  accounts/  roles/  users-roles/  albums/  photos/
```

Each domain has two changesets: DDL, then seed. The master applies all six DDL files first, so
foreign keys exist before any row references them.

## Connection properties

| Property | Default |
|---|---|
| `db.host` | `localhost` |
| `db.port` | `3306` |
| `db.name` | `photo_app` |
| `db.username` | `photo_app_user` |
| `db.password` | `password` |

The compose job overrides `db.host` to `photo-app-mysql` and takes credentials from `.env`.

## How it gets run

**Locally:**

```bash
cd photo-app-api
bash tools/local/setup.sh --status   # show pending changesets, change nothing
bash tools/local/setup.sh            # apply
bash tools/local/setup.sh --drop     # DESTRUCTIVE: drop everything, re-apply
```

**In Docker:** the `photo-app-liquibase` one-shot job bind-mounts this directory read-only and
runs `mvn liquibase:update` against `photo-app-mysql` before any business service starts. It is
idempotent, so `stack.sh` removes the container once it has exited 0.

**Directly with Maven** — you must `cd` into this module first:

```bash
cd photo-app-api/database
mvn liquibase:update
```

The pom's `searchPath` is the relative path `src/main/resources`, which Liquibase resolves
against the **working directory**, not the pom's location. Using `-f` from elsewhere makes the
changelog unresolvable.

## Why image builds target `api-parent/pom.xml`

`liquibase:update` is bound to the **`process-resources`** phase here. Building the root reactor
(which aggregates `api-parent` *and* this module) would therefore try to reach a live database
during an image build. The `Dockerfile` builds from `api-parent/pom.xml` to avoid exactly that.

## Seed data

100 users, 2 roles, 100 user-role links, 199 accounts, 597 albums, 5,970 photos. All seeded users
share one BCrypt hash.

## See also

- [../docs/DATABASE.md](../docs/DATABASE.md) — the full workflow for adding a migration
- [../tools/local/setup.sh](../tools/local/setup.sh) — the bootstrap script
