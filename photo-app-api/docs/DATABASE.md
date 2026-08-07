# Database and schema changes

## Overview

One MySQL 8.4 database, `photo_app`, shared by all five business services. Schema is owned
entirely by **Liquibase**, centralized in the **`database/`** module.

Three rules, and they are not negotiable:

1. **Liquibase only.** Never Flyway, never Hibernate `ddl-auto`. Every `*-dev.properties` sets
   `spring.jpa.hibernate.ddl-auto=none`; the application never creates or alters a table.
2. **Centralized, never per-service.** All changelogs live in `database/`, even though five
   different services read the resulting tables. There is no per-service migration directory.
3. **A changeset is immutable once applied.** Liquibase stores a checksum. Editing an applied
   changeset makes the next `update` fail with a checksum mismatch. Fix forward with a new one.

Rule 1 is enforced by absence, not by configuration: Flyway is not a dependency of any module in
the reactor, and there is no `classpath:db/migration` directory. Four leftover `spring.flyway.*`
properties in `photo-app-users-service-prod.properties` were deleted on 2026-08-02 — they were
inert, but they contradicted this rule and would have become live the moment the dependency ever
appeared.

## Module layout

```
database/
├── pom.xml                                  liquibase-maven-plugin + MySQL driver
├── docs/
│   ├── create_db_and_user.sql               bootstrap schema + user
│   └── drop_tables.sql
└── src/main/resources/db/changelog/
    ├── changelog-master.xml                 the include list — order is execution order
    ├── users/       users_202605301400.xml  (DDL)   users_202605301520.xml  (seed)
    ├── accounts/    accounts_202605301420.xml       accounts_202605301530.xml
    ├── roles/       roles_202605301430.xml          roles_202605301510.xml
    ├── users-roles/ user_roles_202605301440.xml     user_roles_202605301540.xml
    ├── albums/      albums_202605301450.xml         albums_202605301550.xml
    └── photos/      photos_202605301500.xml         photos_202605301600.xml
```

Each domain has two files: DDL first, seed data second. `changelog-master.xml` applies all six
DDL changesets, then all six seed changesets — so foreign keys exist before any row references
them. Changeset ids are sequential integers `1`–`12`, author `ossantvng`.

The plugin is configured in `database/pom.xml` with parameterized connection properties:

| Property | Default |
|---|---|
| `db.host` | `localhost` |
| `db.port` | `3306` |
| `db.name` | `photo_app` |
| `db.username` | `photo_app_user` |
| `db.password` | `password` |

`liquibase:update` is bound to the `process-resources` phase. That is why image builds target
`api-parent/pom.xml` rather than the root pom — building the root reactor would try to reach a
database.

## Adding a migration

### 1. Create the changeset file

Under the matching domain directory, named `<domain>_<yyyyMMddHHmm>.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        https://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.0.xsd">

    <changeSet id="13" author="ossantvng">
        <addColumn tableName="albums">
            <column name="cover_photo_id" type="BIGINT"/>
        </addColumn>
    </changeSet>

</databaseChangeLog>
```

Use the next free integer id — `13` at the time of writing. The `id` + `author` + filename
triple is what Liquibase records in `DATABASECHANGELOG`.

### 2. Register it in the master changelog

Append to `database/src/main/resources/db/changelog/changelog-master.xml`. **Order in this file
is execution order**, so a new changeset goes at the end unless it must precede existing seed
data.

```xml
<include file="db/changelog/albums/albums_202608021500.xml"/>
```

A changeset file that is not included in the master is simply never run — no warning.

### 3. Check what will happen, before it happens

```bash
cd photo-app-api
bash tools/local/setup.sh --status
```

Lists pending changesets and changes nothing. Run this first, every time.

### 4. Apply it locally

```bash
bash tools/local/setup.sh
```

`setup.sh` optionally creates the schema and user (only if `DB_ROOT_PASSWORD` is set), then
delegates to the `database` module's own plugin. Connection settings come from the environment
with the same defaults as the pom: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`.

Against the Dockerized MySQL (which publishes 3306 to the host), the defaults already match —
no arguments needed.

If you prefer Maven directly, you must `cd` into the module first:

```bash
cd photo-app-api/database
mvn liquibase:update
```

`cd` is not optional. The pom's `searchPath` is the relative path `src/main/resources`, which
Liquibase resolves against the **working directory**, not the pom's location. Running with `-f`
from elsewhere makes the changelog unresolvable.

### 5. Verify

```bash
bash tools/local/setup.sh --status     # should report nothing pending
```

Or inspect Liquibase's own bookkeeping:

```bash
docker exec -it photo-app-mysql mysql -u photo_app_user -p photo_app \
  -e "SELECT ID, AUTHOR, FILENAME, DATEEXECUTED, EXECTYPE FROM DATABASECHANGELOG ORDER BY ORDEREXECUTED DESC LIMIT 5;"
```

### 6. Commit the changeset

```bash
git add database/src/main/resources/db/changelog/
git commit -m "Add cover_photo_id to albums"
```

This is the step that makes the migration real for everyone else. The `photo-app-liquibase`
container bind-mounts `./database` read-only and runs `mvn liquibase:update` against
`photo-app-mysql` before any business service starts. So once the file is committed, anyone who
runs `stack.sh up` gets the new schema automatically — no extra step, no manual SQL.

An uncommitted changeset works on your machine and nowhere else.

### Destructive reset

```bash
bash tools/local/setup.sh --drop
```

Prompts for the database name to confirm, then `liquibase:dropAll` followed by a full
re-`update`. Every row is destroyed and the seed data is recreated. There is deliberately no
production equivalent of `setup.sh` — it is a developer convenience only.

## Idempotency

The `photo-app-liquibase` container is safe to re-run. Liquibase consults `DATABASECHANGELOG`
and applies only changesets not already recorded there. Starting the stack ten times applies the
migrations once.

This is what lets `tools/local/stack.sh` remove the job container after a successful run: nothing
is lost by running it again next time. The same property holds for `setup.sh`.

## Seed data

Applied by the six seed changesets, verified against a freshly migrated database:

| Table | Rows | Notes |
|---|---|---|
| `users` | 100 | `admin`, then `user1`…`user99` |
| `roles` | 2 | `ROLE_ADMIN`, `ROLE_USER` |
| `user_roles` | 100 | one role per user; `admin` holds `ROLE_ADMIN` |
| `accounts` | 199 | roughly two per user |
| `albums` | 597 | roughly three per account |
| `photos` | 5970 | ten per album |

All seeded users share one BCrypt hash, so they all have the same password. The plaintext is in
`docs/notes/users.txt` — this is seed data for a local dev stack and nothing else.

Every table carries `id`, `version` (optimistic locking), `created_at` and `updated_at`,
matching `BaseEntity` in `photo-app-entity-model-lib`.

## Connecting with an external SQL client

DBeaver, MySQL Workbench or any JDBC client connects directly — `docker-compose.yml` publishes
`3306:3306`, so no proxy, tunnel or `docker exec` is needed. It is a stock `mysql:8.4` instance.

| Setting | Value |
|---|---|
| Host | `localhost` |
| Port | `3306` |
| Database | `photo_app` (or whatever `MYSQL_DATABASE` is set to) |
| Driver | MySQL 8.x — the standard `com.mysql.cj.jdbc.Driver` |

JDBC URL: `jdbc:mysql://localhost:3306/photo_app`

**Credentials live in `.env`** and are deliberately not reproduced here. Use `MYSQL_USER` /
`MYSQL_PASSWORD` for the application account, or `MYSQL_ROOT_PASSWORD` for root. That file is
gitignored; keep it that way and do not paste its values into tickets, screenshots or this doc.

Only works while `photo-app-mysql` is up — start the stack with `bash tools/local/stack.sh up`.
The container publishing the port is the whole mechanism, so a stopped stack means a refused
connection, not a misconfigured client.

> Local dev inspection only. Production access patterns — RDS, a bastion host, an SSH or SSM
> tunnel, least-privilege read-only accounts — are a separate concern and are not covered here.

## See also

- [ARCHITECTURE.md](ARCHITECTURE.md) — how the services relate to this schema
- [RUNNING.md](RUNNING.md) — starting MySQL standalone for native runs; what `down -v` destroys
- [../database/docs/create_db_and_user.sql](../database/docs/create_db_and_user.sql) — manual bootstrap
