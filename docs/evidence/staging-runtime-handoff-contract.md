# Staging runtime handoff contract

This document is the non-secret input contract for the FUN staging Terraform
root. It records the live K8S gateway and foundation references that were
checked read-only on 2026-09-17. It contains no secret values, private keys,
public-key material, or Terraform state.

## Read-only control-plane observation (2026-09-17)

The latest read-only audit used AWS profile `study-process` in account
`638612472889`, region `us-east-1`. API Gateway HTTP API `qcm8l43flb`
currently exposes `GET /health` and `POST /api/auth/login`; both routes have
`AuthorizationType=NONE`. RDS `oficina-phase3-staging-postgres` is
`available`, PostgreSQL `16.15`, `db.t4g.micro`, private, `20 GiB`, with
one-day backup retention. Staging launcher roles exist. No Lambda function
whose name starts with `oficina-phase3` was present. The three FUN runtime
secret slots (`customer_signing_key`, `authorizer_trust`, and
`rds_ca_certificate`) remain missing. Lambda layer-list calls were denied by
the cross-account resource policy.

This observation is control-plane context only. It does not prove FUN readiness
and does not authorize a Terraform apply or deployment.

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

The object metadata identifies source commit `6a3def0175f5dbc9c981aa905b34ea6c9f72a1a9`. Before any deployment,
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
The staging secret preparation script downloads the regional RDS bundle from
`https://truststore.pki.rds.amazonaws.com/us-east-1/us-east-1-bundle.pem` and
rejects bundles at or above 64 KiB. A normal retry creates only missing target
secrets and preserves existing customer signing and authorizer trust material;
when those two secrets already exist, the script requires a reviewed
non-secret key metadata file and verifies its public JWK against both stored
key representations before reporting it. Full rotation requires an explicit
reviewed rotation switch.

## Remaining reviewed inputs

These are required before a plan can be accepted and must come from the APP
signing-key receipt or the approved release record:

```text
customer_key_id              = customer-2026-09
staff_key_id                 = staff-2026-09
customer_public_keys         = docs/evidence/staging-key-metadata.json
planned_monthly_invocations  = { challenge = <n>, verification = <n>, authorizer = <n>, notification = <n> }
newrelic_java_slim_layer_arn = arn:aws:lambda:us-east-1:451483290750:layer:NewRelicJava17:29
newrelic_extension_layer_arn = arn:aws:lambda:us-east-1:451483290750:layer:NewRelicLambdaExtension:77
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
    "customer_signing_key": "arn:aws:secretsmanager:us-east-1:638612472889:secret:oficina/staging/customer-signing-key-HvT5GG",
    "authorizer_trust": "arn:aws:secretsmanager:us-east-1:638612472889:secret:oficina/staging/authorizer-trust-HN5r7N",
    "rds_ca_certificate": "arn:aws:secretsmanager:us-east-1:638612472889:secret:oficina/staging/rds-ca-certificate-IfnhR3"
  },
  "customer_key_id": "customer-2026-09",
  "staff_key_id": "staff-2026-09",
  "customer_public_keys": { "customer-2026-09": { "kty": "RSA", "alg": "RS256", "use": "sig", "n": "yH0KNCUwLYxw_X7w2_n_Q-F_PP-wYt3euzpUdDNTwd9ZfUavYiXxDOORW5ZVbWSx8t-VEJIO2CTVz6BrsnbW8-poSAW_6eB3XGifxaV4Kljv1CPQJDSGHR7LbvwI1-o6rrlA-tu2F5oxRgJ7xEQ3Mo_MgeEXw0uCCTamPTLdv3g4csyaQ6uHhosE1kKLp5ggVxiPBps_0eUO_CEq4J0eZZXfpsoMhJfiyhlef5c7wqYB1ZWn6W-zFHSsvKEmDi6tDYShczzLGmfOqax1LU-BoT5b_IIc45qtn8ps3wQA9sgWDrS8wyEBvC7Gmh8bsNO5gD2DCnw2VaFt2vYTjDtgQQ", "e": "AQAB" } },
  "ses_sender_email": "rafaelxv.dev@gmail.com",
  "ses_sandbox_mode": true,
  "approved_secret_count": 16,
  "planned_monthly_invocations": { "challenge": "<n>", "verification": "<n>", "authorizer": "<n>", "notification": "<n>" },
  "operator_email": "rafaelxv.dev@gmail.com",
  "newrelic_ingest_secret_arn": "arn:aws:secretsmanager:us-east-1:638612472889:secret:oficina/staging/newrelic-ingest-juFFoQ",
  "newrelic_java_slim_layer_arn": "arn:aws:lambda:us-east-1:451483290750:layer:NewRelicJava17:29",
  "newrelic_extension_layer_arn": "arn:aws:lambda:us-east-1:451483290750:layer:NewRelicLambdaExtension:77",
  "ownership_handoff_reviewed": true
}
```

Do not run `terraform apply` from this contract. The Functions launcher remains
fail-closed until ownership, artifact, secret inventory, cloud-window and
executor wiring reviews are complete.
