# Changelog

This record reconstructs engineering milestones from Git history. Dates below are **target commit dates**, not GitHub Release publication dates or deployment dates. For example, the `v0.1.0` commit is dated 2026-09-06 and its annotated tag was created on 2026-09-07. Tag existence alone does not prove deployment success.

## Unreleased - Finance development

- `feature/finance-recorder` includes [`ac11710`](https://github.com/maruko33/finguard/commit/ac11710467c2ee7459be13b6b18e0050df767295), dated 2026-10-17: six-table Finance foundation, existing-domain separation, Money value object, JPA mappings, and PostgreSQL constraint/mapping tests.
- The [committed verification record](https://github.com/maruko33/finguard/blob/ac11710467c2ee7459be13b6b18e0050df767295/services/transaction-api/docs/phase9/verification.md) reports 11 passing Java tests. It is a historical record, not a fresh run for this documentation change.
- Internal manual Ledger Create/Read is implemented and tested in the local development worktree; it is not included in `ac11710` or a published version tag. Full Finance CRUD remains **In Progress**: Update, Soft Delete, and List are planned next.
- Angular, authenticated Finance endpoints, and AI classification remain **Planned**. No Phase 9 release tag is claimed.

## v0.8.5 - 2026-10-10

[Tag](https://github.com/maruko33/finguard/tree/v0.8.5) / [`ebc5e41`](https://github.com/maruko33/finguard/commit/ebc5e41794b0992e7e160acaf114381e02f5d06e)

- Move the API to public subnets with a public IP, retaining ingress restricted to the ALB security group; remove NAT Gateways and private application subnets.
- Replace the polling ECS risk-worker service with an SQS-triggered Python Lambda; retain private, single-AZ RDS PostgreSQL.
- Add the local Maven/Compose environment wrapper and parameterized local database configuration.
- The preceding transition commit `cc433c0` and this tag together capture the Phase 8.5 infrastructure changes.
- The annotated tag records E2E verification. [Historical smoke-test observations and their provenance](docs/verification/phase-8.5.md) are documented separately; no current deployment or measured billing savings is asserted.

[Architecture](docs/architecture/phase-8.5.md) / [Decision](docs/decisions/0001-cost-optimization.md)

## v0.8.0 - 2026-10-05

[Tag](https://github.com/maruko33/finguard/tree/v0.8.0) / [`d0ad398`](https://github.com/maruko33/finguard/commit/d0ad3987d4b7220dfb88522fd980e6e736cc977e)

- Add the Terraform AWS baseline: VPC across two AZs, public ALB/NAT subnets, private application subnets, and private RDS subnets.
- Configure two NAT Gateways, private Fargate API and worker services, ECR, IAM, security groups, and logging.
- Configure PostgreSQL 17 with managed master credentials and `multi_az = false`; desired task count is one per ECS service.
- This is the snapshot previously referred to as the Phase 8.1 private-ECS architecture. This documentation uses Phase 8.0 to match the actual `v0.8.0` tag; no `v0.8.1` tag exists in the audited inventory.

[Architecture](docs/architecture/phase-8.0.md)

## v0.7.0 - 2026-10-02

[Tag](https://github.com/maruko33/finguard/tree/v0.7.0) / [`8dce2af`](https://github.com/maruko33/finguard/commit/8dce2af25ce2773d5f8d0b1b244b393ac4077a09)

- Define the transaction SQS queue, dead-letter queue, and redrive settings in Terraform.

## v0.6.0 - 2026-09-21

[Tag](https://github.com/maruko33/finguard/tree/v0.6.0) / [`636ef7e`](https://github.com/maruko33/finguard/commit/636ef7e9737100c77313b937fc286ec64052dbe6)

- Publish transaction-created events from the Java API to SQS.
- Add the Python polling SQS consumer and event-publication tests; database save and queue publication remain separate operations.

## v0.5.0 - 2026-09-19

[Tag](https://github.com/maruko33/finguard/tree/v0.5.0) / [`95bc4ff`](https://github.com/maruko33/finguard/commit/95bc4ff5dec553cd881e30daf40210d905f3991f)

- Add Python transaction/risk models, deterministic amount-based evaluation, and low/medium/high risk tests.
- This worker is rules-based, not AI/ML inference.

## v0.4.0 - 2026-09-16

Local tag / [`21573f6`](https://github.com/maruko33/finguard/commit/21573f6)

- Add Docker Compose orchestration for the API and PostgreSQL, with persistent database storage and an environment example.
- The tag exists locally but was absent from GitHub in the 2026-10-20 remote-ref audit. The commit is part of the remote branch history; no remote tag or GitHub Release is implied.

## v0.3.0 - 2026-09-13

[Tag](https://github.com/maruko33/finguard/tree/v0.3.0) / [`c9dc1a1`](https://github.com/maruko33/finguard/commit/c9dc1a1e2a072246d4d154539e49ebb7ec0625a0)

- Containerize the Java API using a Java 21 runtime and a prebuilt Maven JAR.
- This is a runtime image; a multi-stage Maven build is not implemented in this snapshot.

## v0.2.0 - 2026-09-07

[Tag](https://github.com/maruko33/finguard/tree/v0.2.0) / [`cd861ae`](https://github.com/maruko33/finguard/commit/cd861ae84ac0f85d16a52f32f48d6ae9ff22ad62)

- Add Spring Data JPA/PostgreSQL persistence and Flyway V1 for the transaction table.
- Retain the application repository boundary while introducing the PostgreSQL implementation.

## v0.1.0 - 2026-09-06

[Tag](https://github.com/maruko33/finguard/tree/v0.1.0) / [`0e749e0`](https://github.com/maruko33/finguard/commit/0e749e0b45c34ed87caeb8f1445bb153bf675e36)

- Complete transaction creation and lookup with an in-memory repository, request validation, error handling, and initial tests.
