# Phase 8.5: Historical E2E smoke-test record

This record documents recovered historical evidence from the **2026-10-10** Phase 8.5 smoke test in **AWS ca-central-1**. The project owner supplied these details from original terminal output shared during that verification session in a ChatGPT project conversation. It describes that historical test, not the present AWS deployment state. No AWS checks or E2E test reruns were performed to prepare this document.

## Provenance

| Item | Evidence available |
| --- | --- |
| Observation date | 2026-10-10, as reported by the project owner |
| Confirmation date | 2026-10-20, during the repository documentation review |
| Observation source | Original terminal output shared by the project owner in the Phase 8.5 ChatGPT project conversation; selected details were recovered and supplied during this documentation review |
| Environment | AWS ca-central-1 |
| Associated code snapshot | [`v0.8.5`](https://github.com/maruko33/finguard/tree/v0.8.5), commit [`ebc5e41794b0992e7e160acaf114381e02f5d06e`](https://github.com/maruko33/finguard/commit/ebc5e41794b0992e7e160acaf114381e02f5d06e), dated 2026-10-10 |
| Version-control corroboration | The annotated tag states: `Phase 8.5: NAT-free ECS, private RDS, SQS-triggered Lambda, E2E verified` |
| Evidence retained here | Request amount/currency, transaction ID, response status, matching Lambda transaction ID and log timestamp, risk result, and specific queue attributes |
| Original artifact boundary | This document transcribes selected historical evidence. The complete original terminal transcript, screenshots, HTTP response body, and CloudWatch log export have not been committed with this record |
| Exact deployed identity | Deployed image digest and task-definition revision were not captured. Lambda version, log group/stream, and AWS invocation/request IDs are not included in the supplied evidence; the application transaction ID and one log timestamp are available below |

The tag annotation corroborates that E2E verification was recorded at the milestone. It does not independently establish each result below or prove that the deployed image exactly matched the tagged source. The detailed results below are recovered conversation evidence supplied by the owner, not a new AWS inspection or a verbatim reproduction of the entire transcript.

## Recovered observations and correlation

The following table transcribes the supplied evidence; it is not a newly generated terminal response or CloudWatch log.

| Check | Observed result on 2026-10-10 | What the observation supports |
| --- | --- | --- |
| ECS API service | **Running 1/1** | One task was running against the desired count at inspection |
| ALB health check | **HTTP 200** | The reported health request succeeded |
| API request | **POST /transactions**, amount **6000.00 CAD** | The submitted transaction fields recovered from the historical test output |
| API response | **HTTP 201**, transaction ID `fff4ca01-d37f-4171-8efe-497cb6f1622c` | Creation returned the reported application transaction ID |
| Lambda log | **2026-10-10 19:43:44 UTC**, referencing transaction ID `fff4ca01-d37f-4171-8efe-497cb6f1622c` | The same application transaction ID correlates the API response with asynchronous worker evaluation |
| Risk evaluation | Score **80**, level **HIGH**, reason **HIGH_TRANSACTION_AMOUNT** | The worker produced the supplied risk result for the correlated transaction |
| Main SQS queue | `ApproximateNumberOfMessages: 0` | No visible messages were reported by this approximate count |
| Main SQS queue | `ApproximateNumberOfMessagesNotVisible: 0` | No in-flight messages were reported by this approximate count |
| DLQ | `ApproximateNumberOfMessages: 0` | No visible messages were reported by the DLQ's approximate count |

The matching transaction ID provides historical request-to-worker correlation. The Lambda timestamp applies to that recovered log entry; exact timestamps for the HTTP and queue checks, their polling sequence, and an independently queried database row are not supplied. Queue metrics are approximate point-in-time observations, not a guarantee of exactly-once delivery, permanent emptiness, or successful processing of every event.

## Source corroboration

The following inspectable code explains the intended path at the associated snapshot. Source inspection is separate from evidence that a deployed request traversed every component.

1. [`TransactionController.java`](https://github.com/maruko33/finguard/blob/ebc5e41794b0992e7e160acaf114381e02f5d06e/services/transaction-api/src/main/java/com/finguard/transactionapi/transaction/controller/TransactionController.java) defines `POST /transactions` and returns HTTP 201 on successful creation.
2. [`TransactionService.java`](https://github.com/maruko33/finguard/blob/ebc5e41794b0992e7e160acaf114381e02f5d06e/services/transaction-api/src/main/java/com/finguard/transactionapi/transaction/service/TransactionService.java) saves a transaction, then publishes a transaction-created event. This supports the intended database-to-queue flow; no separate SQL assertion or retained row is claimed here.
3. [`lambda.tf`](https://github.com/maruko33/finguard/blob/ebc5e41794b0992e7e160acaf114381e02f5d06e/infra/terraform/lambda.tf) connects the transaction queue to a Python 3.12 Lambda with batch size 1. [`main.tf`](https://github.com/maruko33/finguard/blob/ebc5e41794b0992e7e160acaf114381e02f5d06e/infra/terraform/main.tf) defines the queue's DLQ redrive policy.
4. [`lambda_handler.py`](https://github.com/maruko33/finguard/blob/ebc5e41794b0992e7e160acaf114381e02f5d06e/services/risk-worker/src/risk_worker/lambda_handler.py) parses the queued event, invokes the evaluator, and logs its result. It does not persist a risk score or update the transaction status.
5. [`risk_engine.py`](https://github.com/maruko33/finguard/blob/ebc5e41794b0992e7e160acaf114381e02f5d06e/services/risk-worker/src/risk_worker/risk_engine.py) returns `80 / HIGH` for amounts at least `5000`. This is a deterministic rule, not AI classification. The supplied historical request amount is `6000.00 CAD`, which is consistent with this rule; the amount was recovered from the test evidence rather than inferred from the score.
6. [`alb.tf`](https://github.com/maruko33/finguard/blob/ebc5e41794b0992e7e160acaf114381e02f5d06e/infra/terraform/alb.tf) configures `/api/v1/health` with matcher `200`. That configuration alone is not proof of the reported HTTP response.

## Scope of the result

Taken together, the recovered observations support the historical E2E smoke-test conclusion for API creation and asynchronous risk evaluation, including correlation through the same transaction ID. They do not independently verify the PostgreSQL row. The complete original transcript and deployed artifact identity are not archived here, so this record does not provide an independently replayable deployment or a full audit trail.

This test does not establish sustained availability, latency, throughput, failover, retry/idempotency behavior, authenticated access, or measured cost savings. It also does not demonstrate completed Finance CRUD, Angular functionality, or AI classification. Those are separate development milestones.

The architecture itself uses one desired API task, single-AZ RDS, and HTTP ingress. See the [Phase 8.5 architecture](../architecture/phase-8.5.md) and [cost-optimization ADR](../decisions/0001-cost-optimization.md) for configuration and tradeoffs.

## Evidence to retain on a future run

A future authorized smoke test should archive the revision and deployed image identity, sanitized commands and responses, timestamps and timezone, correlation IDs, the matching Lambda output, and the precise queue attributes sampled. Database assertions and failure-path tests should be recorded separately when performed. The recovered fields above preserve the evidence now available. No synthetic command output, screenshot, CloudWatch log text, or billing record has been presented as an original artifact. The engineering lesson is to retain evidence when the test occurs, including identifiers that connect each stage.
