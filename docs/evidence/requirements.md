# FUN requirement and evidence matrix

Source audit 2026-09-16 at `471d072`; no live evidence is added. [R4 status](r4-local-status.json) is historical and remains unchanged. The [central R3 matrix](../../../Tech-challenge-15SOAT/docs/phase-3/evidence/requirements.md) distinguishes source from acceptance.

| Requirement | Source evidence | Acceptance gap |
| --- | --- | --- |
| OTP lifecycle | [Challenge model](../challenge-state.md), [CpfAuthenticationTest](../../src/test/java/com/oficina/functions/auth/CpfAuthenticationTest.java) | Real conditional contention, SES throttling and safe failure mapping. |
| JWT/route isolation | [Trust contract](../token-trust.md), [TokenAndRoutePolicyTest](../../src/test/java/com/oficina/functions/auth/TokenAndRoutePolicyTest.java) | Live gateway status, secret initialization and rotation overlap. |
| Async delivery | [Delivery model](../notification-delivery.md), [NotificarStatusTest](../../src/test/java/com/oficina/functions/notification/NotificarStatusTest.java) | SQS visibility/DLQ behavior and residual post-SES duplicate window. |
| Bounded runtime | [Runtime Terraform](../../infra/modules/functions/runtime), [infrastructure verifier](../../tests/verify-infrastructure.ps1) | State handoff, executor IAM, actual quota/cold-start/connection measurements. |
| Release and privacy | [I7 gates](../i7-pipeline-contracts.md), [pipeline tests](../../tests/pipeline-contract.ps1), [TelemetryPrivacyTest](../../src/test/java/com/oficina/functions/observability/TelemetryPrivacyTest.java) | Cloud adapter activation, verified promotion/output receipts and alert delivery. |

Follow the [central release runbook](../../../Tech-challenge-15SOAT/docs/phase-3/runbooks/release-operations.md) for inspected recovery and compatible rollback. Do not bulk-redrive queues or treat SES acceptance as mailbox delivery. No production apply is authorized by this file.
