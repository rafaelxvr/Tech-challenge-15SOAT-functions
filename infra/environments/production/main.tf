provider "aws" {
  region              = "us-east-1"
  allowed_account_ids = [var.account_id]
}
resource "terraform_data" "ownership" {
  lifecycle {
    precondition {
      condition     = var.ownership_handoff_reviewed
      error_message = "Review/import the existing K8S runtime and auth-route ownership before FUN can own these resources."
    }
  }
}
module "monitoring" {
  source                       = "../../modules/functions"
  name                         = var.name
  environment                  = var.environment
  notification_function_name   = "${var.name}-${var.environment}-notification"
  notification_queue_name      = "${var.name}-${var.environment}-notifications.fifo"
  notification_dlq_name        = "${var.name}-${var.environment}-notifications-dlq.fifo"
  function_names               = { for key in ["challenge", "verification", "authorizer", "notification"] : key => "${var.name}-${var.environment}-${key}" }
  operator_email               = var.operator_email
  newrelic_java_slim_layer_arn = var.newrelic_java_slim_layer_arn
  newrelic_extension_layer_arn = var.newrelic_extension_layer_arn
  newrelic_ingest_secret_arn   = var.newrelic_ingest_secret_arn
  depends_on                   = [terraform_data.ownership]
}
module "runtime" {
  source                            = "../../modules/functions/runtime"
  name                              = var.name
  environment                       = var.environment
  aws_region                        = var.aws_region
  account_id                        = var.account_id
  network                           = var.network
  gateway                           = var.gateway
  lambda_artifact                   = var.lambda_artifact
  runtime_secret_arns               = var.runtime_secret_arns
  customer_key_id                   = var.customer_key_id
  staff_key_id                      = var.staff_key_id
  ses_sender_email                  = var.ses_sender_email
  ses_sandbox_mode                  = var.ses_sandbox_mode
  approved_secret_count             = var.approved_secret_count
  planned_monthly_invocations       = var.planned_monthly_invocations
  newrelic_ingest_secret_arn        = var.newrelic_ingest_secret_arn
  newrelic_function_instrumentation = module.monitoring.newrelic_function_instrumentation
  depends_on                        = [terraform_data.ownership]
}
