output "native_alarm_topic_arn" {
  value       = aws_sns_topic.native_alarms.arn
  description = "Native function alarm topic ARN; subscription delivery confirmation is R4 evidence."
}

output "newrelic_function_instrumentation" {
  value       = local.newrelic_function_instrumentation
  description = "Immutable layers and runtime-only New Relic delivery contract for the four reviewed Lambda functions."
  sensitive   = true
}

output "newrelic_extension_secret_access_policy_json" {
  value       = data.aws_iam_policy_document.newrelic_extension_secret_access.json
  description = "Exact least-privilege policy the reviewed Lambda role needs for extension-only runtime key resolution."
}
