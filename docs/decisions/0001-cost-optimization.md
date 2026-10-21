# ADR 0001: Reduce infrastructure cost drivers for the learning environment

- **Status:** Implemented in the historical Phase 8.5 code snapshot; this is a retrospective decision record.
- **Decision reflected in code:** 2026-10-10, [`v0.8.5` / `ebc5e41`](https://github.com/maruko33/finguard/tree/ebc5e41794b0992e7e160acaf114381e02f5d06e).
- **Documented:** 2026-10-20.
- **Previous architecture:** [`v0.8.0` / `d0ad398`](https://github.com/maruko33/finguard/tree/d0ad3987d4b7220dfb88522fd980e6e736cc977e).

## Context

FinGuard progressed from a transaction API to an AWS application with PostgreSQL persistence and asynchronous risk evaluation. The Phase 8.0 Terraform topology placed both the API and a continuously running Python worker in private application subnets. Each of the two AZs had a NAT Gateway for outbound connectivity. The ALB occupied public subnets, while RDS occupied private database subnets.

That design provided a useful exercise in subnet routing and service isolation. The next iteration aimed to reduce recurring infrastructure cost drivers for a learning project while retaining the API, private database, queue, and asynchronous worker responsibilities. No workload measurements, billing comparison, or numerical savings estimate is archived with this decision.

## Decision

1. Place the Fargate API in the two public subnets and enable a public IP for its task. Remove the private application subnet and NAT Gateway resources. Outbound connectivity uses the internet gateway.
2. Retain the ALB as the allowed application ingress path: the API security group permits TCP 8080 from the ALB security group. A public task IP does not add an unrestricted API ingress rule.
3. Replace the always-running Fargate worker service with a Python Lambda triggered by SQS. The handler reuses the existing risk engine and logs its result.
4. Keep RDS PostgreSQL private, accepting TCP 5432 from the API security group. Retain SQS and its dead-letter queue.

The resulting Lambda has no customer-VPC attachment and no RDS connection. It evaluates queued events; it does not write risk scores back to PostgreSQL.

See the [Phase 8.0 architecture](../architecture/phase-8.0.md) and [Phase 8.5 architecture](../architecture/phase-8.5.md) for diagrams and source inventories.

## Evidence in the repository

| Change | Historical source |
| --- | --- |
| Two NAT Gateways and private application routes in the baseline | [`v0.8.0` network.tf](https://github.com/maruko33/finguard/blob/d0ad3987d4b7220dfb88522fd980e6e736cc977e/infra/terraform/network.tf) |
| Private API and worker services, each with one desired task | [`v0.8.0` ecs.tf](https://github.com/maruko33/finguard/blob/d0ad3987d4b7220dfb88522fd980e6e736cc977e/infra/terraform/ecs.tf) |
| Public routing and public-IP API service, with NAT resources removed | [`v0.8.5` network.tf](https://github.com/maruko33/finguard/blob/ebc5e41794b0992e7e160acaf114381e02f5d06e/infra/terraform/network.tf), [ecs.tf](https://github.com/maruko33/finguard/blob/ebc5e41794b0992e7e160acaf114381e02f5d06e/infra/terraform/ecs.tf) |
| SQS-triggered Python 3.12 Lambda; batch size 1 | [`v0.8.5` lambda.tf](https://github.com/maruko33/finguard/blob/ebc5e41794b0992e7e160acaf114381e02f5d06e/infra/terraform/lambda.tf) |
| ALB-only API ingress and private RDS | [`v0.8.5` security.tf](https://github.com/maruko33/finguard/blob/ebc5e41794b0992e7e160acaf114381e02f5d06e/infra/terraform/security.tf), [rds.tf](https://github.com/maruko33/finguard/blob/ebc5e41794b0992e7e160acaf114381e02f5d06e/infra/terraform/rds.tf) |

## Consequences and limits

- Removing two NAT Gateways and the continuously running worker changes the cost structure. **Measured savings are unknown**; neither a percentage nor a dollar amount is claimed. The ALB, API, database, logging, addresses, and service usage still contribute costs.
- The API now depends on a public IP and correctly scoped security groups. This trades private application subnet placement for a simpler outbound path.
- Lambda removes the need to operate a polling worker service. This introduces invocation and event-delivery considerations; the smoke test does not establish retry safety or duplicate suppression.
- The API still has `desired_count = 1`, and RDS has `multi_az = false`. Two-AZ subnet placement does not make this a highly available application deployment.
- The ALB listener is HTTP-only. TLS termination is outside this milestone's implementation.
- The API saves to the database and then publishes to SQS without a transactional outbox. A database write can succeed before event publication fails; atomic database-and-queue delivery is not guaranteed. See [`TransactionService.java`](https://github.com/maruko33/finguard/blob/ebc5e41794b0992e7e160acaf114381e02f5d06e/services/transaction-api/src/main/java/com/finguard/transactionapi/transaction/service/TransactionService.java).

## Alternatives for future evaluation

Retaining private tasks with NAT, using private service endpoints, or retaining an ECS worker are possible alternatives. This retrospective record does not claim that these options were benchmarked or formally evaluated at the time. A future decision should compare required connectivity, workload shape, operational complexity, reliability requirements, and measured costs.

## Verification and engineering lesson

The owner confirmed historical E2E observations from 2026-10-10, including an HTTP 201 transaction response and a Lambda risk result of `80 / HIGH`. The [verification record](../verification/phase-8.5.md) distinguishes those observations from inspectable source and missing raw artifacts. No deployment was rerun for this documentation.

The engineering progression is from implementing network isolation to choosing infrastructure that fits a project's operating constraints. Separating the risk evaluator from its runtime made the ECS-to-Lambda change possible while retaining the same business rules. The next improvement is stronger evidence capture and delivery guarantees, guided by actual requirements and measurements.
