mock_provider "aws" {}

variables {
  name                       = "oficina-phase3"
  environment                = "staging"
  aws_region                 = "us-east-1"
  account_id                 = "123456789012"
  gateway                    = { api_id = "abc123", execution_arn = "arn:aws:execute-api:us-east-1:123456789012:abc123", stage_name = "staging" }
  newrelic_ingest_secret_arn = "arn:aws:secretsmanager:us-east-1:123456789012:secret:oficina/staging/newrelic-ingest-AbCdEf"
  network = {
    private_subnet_ids         = ["subnet-0123456789abcdef0", "subnet-abcdef0123456789"]
    function_security_group_id = "sg-0123456789abcdef0"
  }
  lambda_artifact = {
    s3_bucket         = "oficina-artifacts-123456789012"
    s3_key            = "functions/staging/oficina-functions.jar"
    s3_object_version = "3HL4kqtJlcpXroDTDmjVBH40Nrjfkd"
    sha256_base64     = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    sha256_hex        = "0000000000000000000000000000000000000000000000000000000000000000"
  }
  runtime_secret_arns = {
    auth_lookup          = "arn:aws:secretsmanager:us-east-1:123456789012:secret:oficina/staging/auth-lookup-AAAAAA"
    notification_lookup  = "arn:aws:secretsmanager:us-east-1:123456789012:secret:oficina/staging/notification-lookup-BBBBBB"
    customer_signing_key = "arn:aws:secretsmanager:us-east-1:123456789012:secret:oficina/staging/customer-signing-CCCCCC"
    authorizer_trust     = "arn:aws:secretsmanager:us-east-1:123456789012:secret:oficina/staging/authorizer-trust-DDDDDD"
    rds_ca_certificate   = "arn:aws:secretsmanager:us-east-1:123456789012:secret:oficina/staging/rds-ca-EEEEEE"
  }
  customer_key_id             = "customer-2026-01"
  staff_key_id                = "staff-2026-01"
  ses_sender_email            = "no-reply@example.invalid"
  ses_sandbox_mode            = true
  approved_secret_count       = 16
  planned_monthly_invocations = { challenge = 100, verification = 100, authorizer = 1000, notification = 100 }
  newrelic_function_instrumentation = {
    for key in ["challenge", "verification", "authorizer", "notification"] : key => {
      function_name                      = "oficina-phase3-staging-${key}"
      layers                             = ["arn:aws:lambda:us-east-1:451483290750:layer:NewRelicJava17:29", "arn:aws:lambda:us-east-1:451483290750:layer:NewRelicLambdaExtension:77"]
      environment                        = { NEW_RELIC_LAMBDA_EXTENSION_ENABLED = "true", NEW_RELIC_LAMBDA_EXTENSION_LOGS_ENABLED = "true", NEW_RELIC_APPLICATION_LOGGING_FORWARDING_ENABLED = "false", NEW_RELIC_LICENSE_KEY_SECRET = "arn:aws:secretsmanager:us-east-1:123456789012:secret:oficina/staging/newrelic-ingest-AbCdEf" }
      log_forwarder                      = "newrelic-extension"
      cloudwatch_subscription_filter_arn = ""
    }
  }
}

override_resource {
  target = aws_iam_role.function["challenge"]
  values = { arn = "arn:aws:iam::123456789012:role/oficina-staging-challenge" }
}
override_resource {
  target = aws_iam_role.function["verification"]
  values = { arn = "arn:aws:iam::123456789012:role/oficina-staging-verification" }
}
override_resource {
  target = aws_iam_role.function["authorizer"]
  values = { arn = "arn:aws:iam::123456789012:role/oficina-staging-authorizer" }
}
override_resource {
  target = aws_iam_role.function["notification"]
  values = { arn = "arn:aws:iam::123456789012:role/oficina-staging-notification" }
}

run "mock_plan_requires_reviewed_inputs" {
  command = plan
  assert {
    condition     = var.approved_secret_count == 16 && var.ses_sandbox_mode && var.lambda_artifact.sha256_hex != "" && local.planned_monthly_gb_seconds <= 200000
    error_message = "A mocked plan must still require the reviewed secret inventory, sandbox setting and immutable artifact digest."
  }
}

run "fifo_delivery_is_encrypted_and_bounded" {
  command = apply
  assert {
    condition = (
      aws_sqs_queue.notifications.fifo_queue && aws_sqs_queue.notification_dlq.fifo_queue &&
      aws_sqs_queue.notifications.sqs_managed_sse_enabled && aws_sqs_queue.notification_dlq.sqs_managed_sse_enabled &&
      aws_sqs_queue.notifications.message_retention_seconds == 345600 && aws_sqs_queue.notification_dlq.message_retention_seconds == 1209600 &&
      aws_sqs_queue.notifications.visibility_timeout_seconds == 120 && jsondecode(aws_sqs_queue.notifications.redrive_policy).maxReceiveCount == 5
    )
    error_message = "The notification FIFO and DLQ require encryption, 4/14-day retention, 120-second visibility and five receives."
  }
  assert {
    condition = (
      aws_lambda_event_source_mapping.notification.batch_size == 1 &&
      aws_lambda_event_source_mapping.notification.scaling_config[0].maximum_concurrency == 2 &&
      aws_lambda_function.function["notification"].memory_size == 1024 && aws_lambda_function.function["notification"].timeout == 20
    )
    error_message = "The FIFO worker must consume one message at a time with bounded concurrency and 1 GiB/20-second runtime."
  }
}

run "all_functions_have_short_logs_tracing_and_pinned_jar" {
  command = apply
  assert {
    condition = (
      alltrue([for function in values(aws_lambda_function.function) :
        function.runtime == "java17" && function.memory_size == 1024 && function.timeout == 20 &&
        function.tracing_config[0].mode == "Active" && function.s3_object_version == var.lambda_artifact.s3_object_version &&
        function.source_code_hash == var.lambda_artifact.sha256_base64
      ]) && alltrue([for log_group in values(aws_cloudwatch_log_group.function) : log_group.retention_in_days == 1])
    )
    error_message = "All handlers must use the immutable Java 17 artifact, bounded runtime, active tracing and one-day logs."
  }
  assert {
    condition = (
      length(aws_lambda_function.function) == 4 &&
      aws_lambda_function.function["challenge"].handler == "com.oficina.functions.handler.CriarDesafioHandler::handleRequest" &&
      aws_lambda_function.function["verification"].handler == "com.oficina.functions.handler.VerificarDesafioHandler::handleRequest" &&
      aws_lambda_function.function["authorizer"].handler == "com.oficina.functions.handler.AuthorizerHandler::handleRequest" &&
      aws_lambda_function.function["notification"].handler == "com.oficina.functions.handler.NotificacaoHandler::handleRequest"
    )
    error_message = "The one shaded JAR must expose all four reviewed handler entry points."
  }
}

run "functions_consume_fun_owned_newrelic_delivery_contract" {
  command = apply
  assert {
    condition     = alltrue([for key, function in aws_lambda_function.function : function.layers == var.newrelic_function_instrumentation[key].layers && function.environment[0].variables.OFICINA_ENVIRONMENT == var.environment && function.environment[0].variables.NEW_RELIC_LAMBDA_EXTENSION_ENABLED == "true" && function.environment[0].variables.NEW_RELIC_LAMBDA_EXTENSION_LOGS_ENABLED == "true" && function.environment[0].variables.NEW_RELIC_APPLICATION_LOGGING_FORWARDING_ENABLED == "false" && function.environment[0].variables.NEW_RELIC_LICENSE_KEY_SECRET == var.newrelic_function_instrumentation[key].environment.NEW_RELIC_LICENSE_KEY_SECRET]) && alltrue([for policy in values(aws_iam_role_policy.function) : strcontains(policy.policy, "newrelic-ingest-AbCdEf") && strcontains(policy.policy, "secretsmanager:GetSecretValue")])
    error_message = "Every deployed Lambda must consume FUN's immutable layers, extension-only log delivery and exact secret-read policy."
  }
}

run "authorizer_is_verification_only" {
  command = apply
  assert {
    condition = (
      !strcontains(aws_iam_role_policy.function["authorizer"].policy, "dynamodb:") &&
      !strcontains(aws_iam_role_policy.function["authorizer"].policy, "ses:SendEmail") &&
      !strcontains(aws_iam_role_policy.function["authorizer"].policy, "ec2:") &&
      length(aws_lambda_function.function["authorizer"].vpc_config) == 0 &&
      !strcontains(aws_iam_role_policy.function["authorizer"].policy, var.runtime_secret_arns.customer_signing_key) &&
      !contains(keys(aws_lambda_function.function["authorizer"].environment[0].variables), "CUSTOMER_SIGNING_SECRET_ARN")
    )
    error_message = "The authorizer may verify tokens only; it cannot mutate state, send email or receive customer signing material."
  }
}

run "http_authorizer_and_auth_routes_are_exact" {
  command = apply
  assert {
    condition     = aws_apigatewayv2_authorizer.request.authorizer_type == "REQUEST" && aws_apigatewayv2_authorizer.request.authorizer_payload_format_version == "2.0" && aws_apigatewayv2_authorizer.request.enable_simple_responses && aws_apigatewayv2_authorizer.request.authorizer_result_ttl_in_seconds == 0 && length(aws_apigatewayv2_authorizer.request.identity_sources) == 0
    error_message = "HTTP REQUEST authorization must run for absent bearer tokens, without cached results."
  }
  assert {
    condition     = aws_lambda_permission.authorizer.source_arn == "${var.gateway.execution_arn}/authorizers/${aws_apigatewayv2_authorizer.request.id}" && aws_lambda_permission.authorizer.source_account == var.account_id && aws_lambda_permission.authorizer.principal == "apigateway.amazonaws.com" && toset(keys(aws_apigatewayv2_route.auth)) == toset(["POST /api/auth/cpf/desafios", "POST /api/auth/cpf/verificar"]) && alltrue([for route in aws_apigatewayv2_route.auth : route.authorization_type == "NONE"])
    error_message = "FUN owns only the two public CPF auth routes and invokes its verifier from this exact API/authorizer."
  }
  assert {
    condition     = alltrue([for key, permission in aws_lambda_permission.auth : permission.source_arn == "${var.gateway.execution_arn}/staging/${replace(key, "POST /", "POST/")}" && !strcontains(permission.source_arn, "*")])
    error_message = "Auth invoke permission must match the exact API/stage/method/path and Lambda must use shared concurrency."
  }
}

run "per_function_logs_and_fifo_dlq_access_are_scoped" {
  command = apply
  assert {
    condition     = alltrue([for key, policy in aws_iam_role_policy.function : jsondecode(policy.policy).Statement[0].Resource == ["arn:aws:logs:us-east-1:123456789012:log-group:/aws/lambda/oficina-phase3-staging-${key}:log-stream:*"]]) && jsondecode(aws_sqs_queue_redrive_allow_policy.notification.redrive_allow_policy).sourceQueueArns == [aws_sqs_queue.notifications.arn] && jsondecode(aws_sqs_queue_redrive_allow_policy.notification.redrive_allow_policy).redrivePermission == "byQueue"
    error_message = "Each role writes only its own logs; DLQ accepts redrive only from its environment source queue."
  }
  assert {
    condition     = !strcontains(aws_iam_role_policy.function["challenge"].policy, var.runtime_secret_arns.customer_signing_key) && !strcontains(aws_iam_role_policy.function["verification"].policy, "ses:") && !strcontains(aws_iam_role_policy.function["notification"].policy, aws_dynamodb_table.challenge.arn)
    error_message = "Signer, OTP sender and notification ledger privileges must remain independent."
  }
}

run "reject_cross_environment_gateway" {
  command = plan
  variables { gateway = { api_id = "abc123", execution_arn = "arn:aws:execute-api:us-east-1:999999999999:abc123", stage_name = "production" } }
  expect_failures = [var.gateway]
}

run "reject_cross_environment_secret" {
  command = plan
  variables { newrelic_ingest_secret_arn = "arn:aws:secretsmanager:us-east-1:123456789012:secret:oficina/production/newrelic-ingest-AbCdEf" }
  expect_failures = [var.newrelic_ingest_secret_arn]
}

run "handler_secret_arns_match_the_fun_resolver_contract" {
  command = apply
  assert {
    condition = (
      aws_lambda_function.function["challenge"].environment[0].variables["DATABASE_SECRET_ARN"] == var.runtime_secret_arns.auth_lookup &&
      aws_lambda_function.function["challenge"].environment[0].variables["RDS_CA_CERT_SECRET_ARN"] == var.runtime_secret_arns.rds_ca_certificate &&
      !contains(keys(aws_lambda_function.function["challenge"].environment[0].variables), "CUSTOMER_SIGNING_SECRET_ARN") &&
      aws_lambda_function.function["verification"].environment[0].variables["DATABASE_SECRET_ARN"] == var.runtime_secret_arns.auth_lookup &&
      aws_lambda_function.function["verification"].environment[0].variables["CUSTOMER_SIGNING_SECRET_ARN"] == var.runtime_secret_arns.customer_signing_key &&
      aws_lambda_function.function["authorizer"].environment[0].variables["AUTHORIZER_TRUST_SECRET_ARN"] == var.runtime_secret_arns.authorizer_trust &&
      !contains(keys(aws_lambda_function.function["authorizer"].environment[0].variables), "DATABASE_SECRET_ARN") &&
      aws_lambda_function.function["notification"].environment[0].variables["DATABASE_SECRET_ARN"] == var.runtime_secret_arns.notification_lookup &&
      aws_lambda_function.function["notification"].environment[0].variables["RDS_CA_CERT_SECRET_ARN"] == var.runtime_secret_arns.rds_ca_certificate
    )
    error_message = "Each Lambda must receive only the SecretResolver ARN settings declared for its handler."
  }
}

run "runtime_permissions_are_ledger_and_queue_scoped" {
  command = apply
  assert {
    condition     = contains(local.role_statements.verification[0].Action, "dynamodb:ConditionCheckItem") && !strcontains(aws_iam_role_policy.function["verification"].policy, "dynamodb:TransactWriteItems")
    error_message = "The verification CAS transaction needs ConditionCheckItem on its exact table, not an unsupported transaction action."
  }
  assert {
    condition = (
      strcontains(aws_iam_role_policy.function["notification"].policy, "sqs:DeleteMessage") &&
      strcontains(aws_iam_role_policy.function["notification"].policy, aws_sqs_queue.notifications.arn) &&
      strcontains(aws_iam_role_policy.function["notification"].policy, "dynamodb:UpdateItem") &&
      strcontains(aws_iam_role_policy.function["notification"].policy, aws_dynamodb_table.delivery.arn) &&
      jsondecode(aws_iam_policy.notification_publisher.policy).Statement[0].Action == ["sqs:SendMessage"] &&
      jsondecode(aws_iam_policy.notification_publisher.policy).Statement[0].Resource == aws_sqs_queue.notifications.arn
    )
    error_message = "Notification consumes only its source queue, updates only its ledger and the publisher can only send to this FIFO queue."
  }
}

run "secret_budget_is_a_deployment_precondition" {
  command = apply
  assert {
    condition     = var.approved_secret_count == 16 && length(values(var.runtime_secret_arns)) == 5
    error_message = "I5 must use existing named secrets without exceeding the approved inventory."
  }
}
