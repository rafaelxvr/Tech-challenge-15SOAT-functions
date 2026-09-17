mock_provider "aws" {}

variables {
  name                       = "oficina-phase3"
  environment                = "production"
  aws_region                 = "us-east-1"
  account_id                 = "123456789012"
  gateway                    = { api_id = "abc123", execution_arn = "arn:aws:execute-api:us-east-1:123456789012:abc123", stage_name = "production" }
  newrelic_ingest_secret_arn = "arn:aws:secretsmanager:us-east-1:123456789012:secret:oficina/production/newrelic-ingest-AbCdEf"
  network = {
    private_subnet_ids         = ["subnet-0123456789abcdef0", "subnet-abcdef0123456789"]
    function_security_group_id = "sg-0123456789abcdef0"
  }
  lambda_artifact = {
    s3_bucket         = "oficina-artifacts-123456789012"
    s3_key            = "functions/production/oficina-functions.jar"
    s3_object_version = "3HL4kqtJlcpXroDTDmjVBH40Nrjfkd"
    sha256_base64     = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    sha256_hex        = "0000000000000000000000000000000000000000000000000000000000000000"
  }
  runtime_secret_arns = {
    auth_lookup          = "arn:aws:secretsmanager:us-east-1:123456789012:secret:oficina/production/auth-lookup-AAAAAA"
    notification_lookup  = "arn:aws:secretsmanager:us-east-1:123456789012:secret:oficina/production/notification-lookup-BBBBBB"
    customer_signing_key = "arn:aws:secretsmanager:us-east-1:123456789012:secret:oficina/production/customer-signing-CCCCCC"
    authorizer_trust     = "arn:aws:secretsmanager:us-east-1:123456789012:secret:oficina/production/authorizer-trust-DDDDDD"
    rds_ca_certificate   = "arn:aws:secretsmanager:us-east-1:123456789012:secret:oficina/production/rds-ca-EEEEEE"
  }
  customer_key_id              = "customer-2026-01"
  staff_key_id                 = "staff-2026-01"
  ses_sender_email             = "no-reply@example.invalid"
  ses_sandbox_mode             = true
  approved_secret_count        = 16
  planned_monthly_invocations  = { challenge = 100, verification = 100, authorizer = 1000, notification = 100 }
  operator_email               = "operator@example.invalid"
  newrelic_java_slim_layer_arn = "arn:aws:lambda:us-east-1:451483290750:layer:NewRelicJava17:42"
  newrelic_extension_layer_arn = "arn:aws:lambda:us-east-1:451483290750:layer:NewRelicExtension:18"
  customer_public_keys         = { "customer-2026-01" = { kty = "RSA", alg = "RS256", use = "sig", e = "AQAB", n = join("", [for i in range(342) : "A"]) } }
  ownership_handoff_reviewed   = true
}
run "explicit_environment_contract" {
  command = plan
  assert {
    condition     = output.artifact_sha256 == var.lambda_artifact.sha256_hex
    error_message = "The root must preserve the reviewed immutable FUN artifact identity."
  }
}
run "reject_unreviewed_ownership" {
  command = plan
  variables { ownership_handoff_reviewed = false }
  expect_failures = [terraform_data.ownership]
}
