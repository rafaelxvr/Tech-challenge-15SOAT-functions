# I5 serverless infrastructure

`infra/environments/staging` and `infra/environments/production` compose the new `infra/modules/functions/runtime` with the existing native alarms/New Relic module. Java source and the existing handler/secret contracts are unchanged. Terraform 1.15.8, AWS provider 5.100.0 and Java 17 remain pinned.

## Ownership before activation

The existing K8S repository also contains runtime resources and platform-owned auth routes/authorizer. **Do not apply these FUN roots alongside those owners.** `ownership_handoff_reviewed` defaults to false and blocks plans until an operator reviews the state inventory and exact import/removal plan. This source change neither edits K8S nor moves state.

The handoff must cover the four Lambda functions/roles/inline policies/log groups, source FIFO/DLQ, DynamoDB tables, mapping, publisher policy, and the single HTTP authorizer, its permission, two auth integrations/routes/invoke permissions. Import existing identities into this root and remove their former ownership without destroying live resources. The DLQ redrive-allow policy is explicit. Existing alarm/SNS resources, if deployed elsewhere, require the same single-owner review. Gateway API/stage, `/health`, business routes, and APP role attachment stay with their approved owners; this module creates none of them. Review the SSE-KMS to SQS-managed encryption transition for any pre-existing queues and retain old-key consumer access until older encrypted messages have drained.

| Environment | Source prefix | State / lock | Trusted executor input |
|---|---|---|---|
| staging | `releases/functions/staging` | `functions/staging.tfstate` / `.tflock` | `/tmp/oficina/functions_staging.tfvars.json` |
| production | `releases/functions/production` | `functions/production.tfstate` / `.tflock` | `/tmp/oficina/functions_production.tfvars.json` |

## Inputs and resource bounds

Supply the reviewed account, us-east-1, environment, private subnets (at least two), function SG, HTTP API ID/execution ARN and exact stage. The API/stage owner must retain the initial rate 1 request/second and burst 2 without a second stage owner. Supply the immutable shaded JAR bucket/key/VersionId plus matching SHA-256 hex/base64 (verify actual bytes before approval), public customer/staff key IDs, SES sandbox sender and verified recipients, and non-negative monthly invocation forecasts. Four 1-GiB/20-second functions must fit the 200,000 GB-second forecast cap.

Five distinct existing secret ARNs are required in the exact account/environment: auth read-view credential, notification read-view credential, customer signing private key, authorizer trust (public customer key + separate staff HMAC), and RDS CA. The existing environment New Relic ingest secret is also referenced, together with both pinned official layer versions and the operator email. No secret value is passed to Terraform or emitted in outputs; the global approved inventory must still equal 16. These are references to existing inventory, not newly created secrets. APP must first establish the distinct read-only SQL views/roles and validate their denial of writes. IAM possession of a lookup secret does not establish SQL privileges.

| Function | Data access |
|---|---|
| challenge | Exact challenge table; auth DB/CA; SES SendEmail from the exact verified sender identity. No signing key. |
| verification | Exact challenge table; auth DB/CA; exact customer signing secret. No SES. |
| authorizer | Exact trust secret; no DB, DynamoDB, SES, queue, signing private key, VPC attachment or ENI permission. |
| notification | Receive/delete/visibility/attributes on its source FIFO only; its delivery table; notification DB/CA; exact SES sender identity. |

Each role writes only its own environment/account log group (retention one day) and reads the existing New Relic ingest ARN. The three VPC-attached functions receive AWS's documented ENI lifecycle permissions; X-Ray write actions and those VPC operations use required `Resource: *` scopes. There are no wildcard service actions, general secret access, or runtime IAM mutation grants. See [AWS's Lambda VPC permission reference](https://docs.aws.amazon.com/aws-managed-policy/latest/reference/AWSLambdaVPCAccessExecutionRole.html). Network egress/private endpoint readiness is a platform prerequisite.

The encrypted FIFO source retains four days, DLQ fourteen, visibility 120 seconds and redrive after five receives. SQS-managed encryption avoids implicit KMS runtime grants. Only this source queue may redrive to the DLQ. The mapping uses batch 1 and maximum concurrency 2; no reserved/provisioned Lambda concurrency is configured. Separate encrypted DynamoDB challenge/delivery tables use `ttl` and point-in-time recovery. APP receives a publisher policy containing only SendMessage on its environment queue. Existing environment-specific SNS/age/DLQ/throttle alarms are composed without changing their missing-data semantics; subscription confirmation is external evidence.

HTTP API v2 REQUEST authorizer uses payload 2.0/simple responses, TTL 0 and no identity sources, so missing-bearer requests reach the existing F4 handler. Invoke permission names the exact API/authorizer. FUN owns only public `POST /api/auth/cpf/desafios` and `POST /api/auth/cpf/verificar`; their invoke permissions bind API, stage, method and path. No function URLs or result cache. APP business bindings consume `authorizer_id`. [AWS authorizer behavior](https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-lambda-authorizer.html) still requires live 401/403/throttle acceptance checks.

## CI and deployment status

The existing verify workflow retains `mvnw verify` and adds offline pipeline contracts, Terraform fmt/validate/mock tests with read-only provider locks. It receives no OIDC deployment identity. This repository had no reviewed deployment scripts, so the new `start-deploy.ps1` and `deploy.ps1` validate exact artifact/environment/project/state/tfvars inputs and fail closed for every live invocation. `-DryRun` reports `INPUTS_VALIDATED_DEPLOYMENT_DISABLED`; even `-ApplyReviewedPlan` cannot apply. The archive buildspec also fails closed.

Before enabling any deploy-staging job, implement/review immutable versioned artifact upload and byte/digest verification, exact immutable GitHub OIDC subject, fresh approved cloud-window evidence, shared-foundation lock ownership, CodeBuild completion polling, output receipt publishing and staging-tested production promotion. Platform executor IAM must be reviewed for these FUN-owned API Gateway bindings, SQS redrive policies, DynamoDB TTL/PITR, native alarms/SNS and pinned Lambda layers. Do not infer that the existing executor profile already authorizes every new provider action. No cloud readiness is claimed by source verification.

`contracts/outputs-allowlist.json` exports authorizer ID, `customerPublicKeys` (reviewed public RSA JWKs indexed by kid), four function ARNs, source queue URL/ARN, DLQ ARN, publisher policy ARN, artifact digest and native alarm topic ARN. The public JWK input must include the current kid and match the independently initialized signing/trust material; Terraform does not decrypt that material to prove the match. `scripts/export-outputs.ps1` reads an already obtained Terraform output JSON file, rejects missing/sensitive allowlisted fields and drops unknown values. It does not read AWS or state itself and never publishes secret values.

Verification's challenge CAS uses `dynamodb:ConditionCheckItem` alongside GetItem/UpdateItem on its challenge table. Transaction puts/updates use their underlying item actions; no unsupported `dynamodb:TransactWriteItems` IAM action is granted. See [DynamoDB transaction IAM](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/transaction-apis-iam.html).

Run `pwsh -NoProfile -File tests/verify-infrastructure.ps1`. Live TLS/view permission tests, package digest verification, ownership migration, SES delivery, Lambda benchmarks/shared account quota, authorizer status codes and branch/environment protection evidence remain release prerequisites.

Local evidence: verify-infrastructure.ps1 passed 35 pipeline assertions and 17 mocked Terraform runs (12 runtime, 1 monitoring, 2 per environment), with fmt/validate for all four roots/modules. Focused FunctionFactoryTest, SecretResolverTest and NotificacaoHandlerTest passed 8 Java tests. No AWS deployment or live authorization claim is implied. Existing instrumentation.tf changed only in Terraform formatting required by CI.
