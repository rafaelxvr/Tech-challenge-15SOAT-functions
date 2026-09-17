# Staging runtime handoff contract

This document is the non-secret input contract for the FUN staging Terraform
root. It records the live K8S gateway and foundation references that were
checked read-only on 2026-09-17. It contains no secret values, private keys,
public-key material, or Terraform state.

## Live handoff inputs

```text
account_id       = 638612472889
aws_region       = us-east-1
name             = oficina-phase3
environment      = staging
gateway.api_id   = qcm8l43flb
gateway.execution_arn = arn:aws:execute-api:us-east-1:638612472889:qcm8l43flb
gateway.stage_name    = $default
network.private_subnet_ids = subnet-0e49c88c97a118461, subnet-06c013de9577c7f0b
network.function_security_group_id = sg-0665470e2736713ff
newrelic_ingest_secret_arn = arn:aws:secretsmanager:us-east-1:638612472889:secret:oficina/staging/newrelic-ingest-juFFoQ
ses_sender_email = rafaelxv.dev@gmail.com
operator_email   = rafaelxv.dev@gmail.com
ses_sandbox_mode = true
approved_secret_count = 16
```

The gateway is an HTTP API with the `$default` stage and automatic deployment.
The K8S owner currently exposes `GET /health` and `POST /api/auth/login`.
FUN must receive this API ID and execution ARN; it creates the Lambda
authorizer, CPF challenge/verification integrations and their invoke
permissions. The resulting four-field `gateway_handoff` is returned to K8S
before protected application routes are enabled.

## Immutable FUN artifact

The currently available staging JAR was checked with S3 metadata only:

```text
lambda_artifact.s3_bucket         = oficina-phase3-artifacts-16225b7358
lambda_artifact.s3_key            = releases/functions/staging/artifacts/6a3def0/oficina-functions.jar
lambda_artifact.s3_object_version = Zvl6N.comOxmazPU_xz1xGHlnMBza8GM
lambda_artifact.sha256_hex        = cdc4553afd15de062e9c128287e05d198d8900b02c420d1424d41ad237f4efa6
lambda_artifact.sha256_base64     = zcRVOv0V3gYunBKCh+BdGY2JALAsQg0UJNQa0jf076Y=
```

The object metadata identifies source commit `6a3def0`. Before any deployment,
the executor must independently verify the object bytes against both digest
encodings.

## Secret ARN slots

Terraform receives only ARNs. It must never receive a secret value.

| Terraform slot | Current status | Secret JSON contract |
|---|---|---|
| `runtime_secret_arns.auth_lookup` | Existing ARN: `arn:aws:secretsmanager:us-east-1:638612472889:secret:oficina/staging/auth-zNEIrm` | Exact string fields `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` for the auth read view. |
| `runtime_secret_arns.notification_lookup` | Existing ARN: `arn:aws:secretsmanager:us-east-1:638612472889:secret:oficina/staging/notification-l4pVTy` | Exact string fields `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` for the notification read view. |
| `runtime_secret_arns.customer_signing_key` | Missing; create an environment-scoped `oficina/staging/...` secret | Exact JSON object with `CUSTOMER_PRIVATE_KEY_B64`, containing a base64 PKCS#8 RSA private key. Verification only may read this ARN. |
| `runtime_secret_arns.authorizer_trust` | Missing; create an environment-scoped `oficina/staging/...` secret | Exact JSON object with `CUSTOMER_PUBLIC_KEY_B64` and `STAFF_HMAC_SECRET`; values are base64 X.509 RSA public key and the APP staff HMAC raw text respectively. |
| `runtime_secret_arns.rds_ca_certificate` | Missing; create an environment-scoped `oficina/staging/...` secret | SecretString is PEM text beginning `-----BEGIN CERTIFICATE-----` and ending `-----END CERTIFICATE-----`; no JSON wrapper. |

The resolver rejects non-text fields, missing fields, unknown fields, malformed
ARNs, and invalid certificate content. The Lambda environment sets
`DB_CA_PATH=/tmp/oficina/rds-ca.pem` for the three database-connected handlers.

## Remaining reviewed inputs

These are required before a plan can be accepted and must come from the APP
signing-key receipt or the approved release record:

```text
customer_key_id              = <current staging customer kid>
staff_key_id                 = <current staging staff kid>
customer_public_keys         = <public RSA JWK map indexed by customer_key_id>
planned_monthly_invocations  = { challenge = <n>, verification = <n>, authorizer = <n>, notification = <n> }
newrelic_java_slim_layer_arn = arn:aws:lambda:us-east-1:451483290750:layer:NewRelicJava17:<pinned-version>
newrelic_extension_layer_arn = arn:aws:lambda:us-east-1:451483290750:layer:NewRelicExtension:<pinned-version>
ownership_handoff_reviewed   = true
```

The public JWK must contain only `kty`, `alg`, `use`, `n`, and `e`, use RSA /
RS256 / `sig`, use exponent `AQAB`, include the current `customer_key_id`, and
contain at most three keys. The forecast must keep the four-function total at
or below 200,000 GB-seconds; each invocation is budgeted at 20 seconds and
1 GiB.

## Safe tfvars shape

Create `/tmp/oficina/functions_staging.tfvars.json` only in the trusted
deployment workspace. Replace angle-bracket placeholders from reviewed
non-secret receipts; do not put secret values in this file.

```json
{
  "name": "oficina-phase3",
  "environment": "staging",
  "aws_region": "us-east-1",
  "account_id": "638612472889",
  "network": {
    "private_subnet_ids": ["subnet-0e49c88c97a118461", "subnet-06c013de9577c7f0b"],
    "function_security_group_id": "sg-0665470e2736713ff"
  },
  "gateway": {
    "api_id": "qcm8l43flb",
    "execution_arn": "arn:aws:execute-api:us-east-1:638612472889:qcm8l43flb",
    "stage_name": "$default"
  },
  "lambda_artifact": {
    "s3_bucket": "oficina-phase3-artifacts-16225b7358",
    "s3_key": "releases/functions/staging/artifacts/6a3def0/oficina-functions.jar",
    "s3_object_version": "Zvl6N.comOxmazPU_xz1xGHlnMBza8GM",
    "sha256_hex": "cdc4553afd15de062e9c128287e05d198d8900b02c420d1424d41ad237f4efa6",
    "sha256_base64": "zcRVOv0V3gYunBKCh+BdGY2JALAsQg0UJNQa0jf076Y="
  },
  "runtime_secret_arns": {
    "auth_lookup": "arn:aws:secretsmanager:us-east-1:638612472889:secret:oficina/staging/auth-zNEIrm",
    "notification_lookup": "arn:aws:secretsmanager:us-east-1:638612472889:secret:oficina/staging/notification-l4pVTy",
    "customer_signing_key": "<ARN returned after staging secret creation>",
    "authorizer_trust": "<ARN returned after staging secret creation>",
    "rds_ca_certificate": "<ARN returned after staging secret creation>"
  },
  "customer_key_id": "<reviewed staging customer kid>",
  "staff_key_id": "<reviewed staging staff kid>",
  "customer_public_keys": { "<reviewed staging customer kid>": { "kty": "RSA", "alg": "RS256", "use": "sig", "n": "<public modulus>", "e": "AQAB" } },
  "ses_sender_email": "rafaelxv.dev@gmail.com",
  "ses_sandbox_mode": true,
  "approved_secret_count": 16,
  "planned_monthly_invocations": { "challenge": "<n>", "verification": "<n>", "authorizer": "<n>", "notification": "<n>" },
  "operator_email": "rafaelxv.dev@gmail.com",
  "newrelic_ingest_secret_arn": "arn:aws:secretsmanager:us-east-1:638612472889:secret:oficina/staging/newrelic-ingest-juFFoQ",
  "newrelic_java_slim_layer_arn": "arn:aws:lambda:us-east-1:451483290750:layer:NewRelicJava17:<pinned-version>",
  "newrelic_extension_layer_arn": "arn:aws:lambda:us-east-1:451483290750:layer:NewRelicExtension:<pinned-version>",
  "ownership_handoff_reviewed": true
}
```

Do not run `terraform apply` from this contract. The Functions launcher remains
fail-closed until ownership, artifact, secret inventory, cloud-window and
executor wiring reviews are complete.
