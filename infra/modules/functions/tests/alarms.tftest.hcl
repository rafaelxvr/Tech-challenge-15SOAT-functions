mock_provider "aws" {}

variables {
  name                       = "oficina-phase3"
  environment                = "staging"
  notification_function_name = "oficina-phase3-staging-notification"
  notification_queue_name    = "oficina-phase3-staging-notifications.fifo"
  notification_dlq_name      = "oficina-phase3-staging-notifications-dlq.fifo"
  function_names = {
    challenge    = "oficina-phase3-staging-challenge"
    verification = "oficina-phase3-staging-verification"
    authorizer   = "oficina-phase3-staging-authorizer"
    notification = "oficina-phase3-staging-notification"
  }
  operator_email               = "operator@example.invalid"
  newrelic_java_slim_layer_arn = "arn:aws:lambda:us-east-1:451483290750:layer:NewRelicJava17:42"
  newrelic_extension_layer_arn = "arn:aws:lambda:us-east-1:451483290750:layer:NewRelicExtension:18"
  newrelic_ingest_secret_arn   = "arn:aws:secretsmanager:us-east-1:123456789012:secret:oficina/staging/newrelic-ingest-AbCdEf"
}

run "native_alarms_are_exactly_scoped_and_missing_is_unknown" {
  command = plan
  assert {
    condition     = aws_cloudwatch_metric_alarm.notification_source_age.namespace == "AWS/SQS" && aws_cloudwatch_metric_alarm.notification_source_age.metric_name == "ApproximateAgeOfOldestMessage" && aws_cloudwatch_metric_alarm.notification_source_age.threshold == 300 && aws_cloudwatch_metric_alarm.notification_source_age.evaluation_periods == 2 && aws_cloudwatch_metric_alarm.notification_source_age.datapoints_to_alarm == 2 && aws_cloudwatch_metric_alarm.notification_source_age.dimensions.QueueName == var.notification_queue_name
    error_message = "Source age must exceed 300 seconds for two exact-resource one-minute periods."
  }
  assert {
    condition     = aws_cloudwatch_metric_alarm.notification_dlq_depth.threshold == 0 && aws_cloudwatch_metric_alarm.notification_dlq_depth.dimensions.QueueName == var.notification_dlq_name && length(aws_cloudwatch_metric_alarm.lambda_throttles) == 4
    error_message = "DLQ depth and each exact Lambda throttle need native alarms."
  }
  assert {
    condition     = aws_cloudwatch_metric_alarm.notification_source_age.treat_missing_data == "missing" && aws_cloudwatch_metric_alarm.notification_dlq_depth.treat_missing_data == "missing" && alltrue([for alarm in values(aws_cloudwatch_metric_alarm.lambda_throttles) : alarm.treat_missing_data == "missing"]) && aws_sns_topic_subscription.operator.protocol == "email"
    error_message = "Missing telemetry must stay unknown and native SNS requires a verified operator subscription."
  }
  assert {
    condition     = length(local.newrelic_function_instrumentation) == 4 && alltrue([for delivery in values(local.newrelic_function_instrumentation) : length(delivery.layers) == 2 && delivery.layers[0] == var.newrelic_java_slim_layer_arn && delivery.layers[1] == var.newrelic_extension_layer_arn && delivery.environment.NEW_RELIC_LAMBDA_EXTENSION_ENABLED == "true" && delivery.environment.NEW_RELIC_LAMBDA_EXTENSION_LOGS_ENABLED == "true" && delivery.environment.NEW_RELIC_APPLICATION_LOGGING_FORWARDING_ENABLED == "false" && delivery.environment.NEW_RELIC_LICENSE_KEY_SECRET == var.newrelic_ingest_secret_arn && delivery.log_forwarder == "newrelic-extension" && delivery.cloudwatch_subscription_filter_arn == ""])
    error_message = "Each exact Lambda must use pinned Java slim/extension layers, extension-only log forwarding and the existing runtime secret reference."
  }
  assert {
    condition     = local.newrelic_extension_secret_actions == ["secretsmanager:GetSecretValue"] && local.newrelic_extension_secret_resources == [var.newrelic_ingest_secret_arn]
    error_message = "The extension role contract must read only the exact existing New Relic ingest secret."
  }
}
