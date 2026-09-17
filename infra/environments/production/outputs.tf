output "authorizer_id" { value = module.runtime.authorizer_id }
output "gateway_handoff" {
  value = {
    api_id        = var.gateway.api_id
    execution_arn = var.gateway.execution_arn
    authorizer_id = module.runtime.authorizer_id
    environment   = var.environment
  }
  description = "Canonical reviewed handoff consumed by K8S to bind protected routes."
}
output "function_arns" { value = module.runtime.function_arns }
output "notification_queue_url" { value = module.runtime.notification_queue_url }
output "notification_queue_arn" { value = module.runtime.notification_queue_arn }
output "notification_dlq_arn" { value = module.runtime.notification_dlq_arn }
output "notification_publisher_policy_arn" { value = module.runtime.notification_publisher_policy_arn }
output "artifact_sha256" { value = module.runtime.artifact_sha256 }
output "native_alarm_topic_arn" { value = module.monitoring.native_alarm_topic_arn }
output "customer_public_keys" { value = var.customer_public_keys }
