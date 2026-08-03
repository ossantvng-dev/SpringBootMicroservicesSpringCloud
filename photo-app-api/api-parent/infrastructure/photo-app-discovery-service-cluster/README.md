# photo-app-discovery-service-cluster

A peer-aware Eureka configuration, kept for **reference and learning**.

**Not containerized. Not part of the running stack.** `docker-compose.yml` deliberately does not
include it.

## Why it exists

It demonstrates a multi-peer Eureka topology — three nodes registering with each other so the
registry survives losing one. That is what a production deployment would need.

## Why it isn't used

The dockerization round settled on a **single discovery instance** (recorded decision 6 in
[`../../../docs/plans/dockerization-plan.md`](../../../docs/plans/dockerization-plan.md)). One
instance is sufficient for a local stack, and a three-peer cluster would triple the memory
footprint of the discovery tier for no local benefit.

It gets the same treatment as the Config Server's `native` profile: kept in the codebase,
excluded from the running stack.

## If you want to run it

See [`../../../docs/run-eureka-cluster-terminal.txt`](../../../docs/run-eureka-cluster-terminal.txt)
for the original terminal-based instructions.

## Future

Production discovery topology is an open decision — a multi-AZ peer cluster versus a managed
alternative such as AWS Cloud Map. Captured in the AWS backlog of
[`../../../docs/plans/dockerization-plan.md`](../../../docs/plans/dockerization-plan.md).

## See also

- [../photo-app-discovery-service](../photo-app-discovery-service) — the instance actually used
