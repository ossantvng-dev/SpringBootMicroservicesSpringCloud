# photo-app-entity-model-lib

The JPA entities. One shared domain model across all five business services.

**Not a service — a library.** No port, no `main`, no container.

## Contents

| Type | Notes |
|---|---|
| `BaseEntity` | `id`, `version`, `created_at`, `updated_at` — extended by all entities |
| `User` | |
| `Role`, `RoleName` | `ROLE_ADMIN`, `ROLE_USER` |
| `Account`, `AccountType` | `BASIC`, `PREMIUM` |
| `Album` | FK → `accounts` |
| `Photo` | FK → `albums` |

## Shared model, shared schema

All five services map onto the **same** `photo_app` schema. That is a real coupling and a
deliberate one for this round: it keeps the domain consistent and avoids five copies of the same
entity drifting apart. The trade-off is that a schema change can affect several services at once.

The schema itself is owned by Liquibase in the [`database`](../../../database) module — these
entities **describe** the tables, they never create them. Every profile sets
`spring.jpa.hibernate.ddl-auto=none`.

Adding a field means: write the Liquibase changeset first, then add the field here. Not the
reverse.

## `BaseEntity` and Lombok builders

Lombok's `@Builder` does not expose inherited fields, so `id`, `version`, `created_at` and
`updated_at` are not builder targets. This is why MapStruct mappers in
[`../photo-app-commons`](../photo-app-commons) cannot declare
`@Mapping(target = "id", ignore = true)` — there is nothing there to ignore.

## Dependencies

Depends on **nothing internal**. That is what makes it safe for
[`photo-app-commons`](../photo-app-commons) to depend on it without any risk of a cycle.

## Consumed by

All five business services, and `photo-app-commons`.

## Not generic — on purpose

Photo-app-specific by decision. Making it reusable is an explicit **non-goal**; see
[`../../../docs/plans/dockerization-plan.md`](../../../docs/plans/dockerization-plan.md).

## See also

- [../../../docs/DATABASE.md](../../../docs/DATABASE.md) — the schema these map to, and how to change it
