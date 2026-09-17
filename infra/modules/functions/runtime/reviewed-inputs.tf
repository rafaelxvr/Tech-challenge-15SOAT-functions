variable "account_id" {
  type = string
  validation {
    condition     = can(regex("^[0-9]{12}$", var.account_id))
    error_message = "A reviewed AWS account ID is required."
  }
}
variable "gateway" {
  type = object({ api_id = string, execution_arn = string, stage_name = string })
  validation {
    condition     = can(regex("^[a-z0-9]+$", var.gateway.api_id)) && var.gateway.execution_arn == "arn:aws:execute-api:us-east-1:${var.account_id}:${var.gateway.api_id}" && contains([var.environment, "$default"], var.gateway.stage_name)
    error_message = "Gateway references must name the exact reviewed account/region API and environment stage."
  }
}
variable "newrelic_ingest_secret_arn" {
  type = string
  validation {
    condition     = can(regex("^arn:aws:secretsmanager:us-east-1:${var.account_id}:secret:oficina/${var.environment}/newrelic-ingest-[A-Za-z0-9]+$", var.newrelic_ingest_secret_arn))
    error_message = "Only this account/environment's existing New Relic ingest secret is permitted."
  }
}
resource "aws_sqs_queue_redrive_allow_policy" "notification" {
  queue_url            = aws_sqs_queue.notification_dlq.id
  redrive_allow_policy = jsonencode({ redrivePermission = "byQueue", sourceQueueArns = [aws_sqs_queue.notifications.arn] })
}
