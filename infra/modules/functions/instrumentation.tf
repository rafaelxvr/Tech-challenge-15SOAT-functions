locals {
  # This contract is consumed by the reviewed Lambda deployment root. Immutable layer ARNs keep
  # the Java slim agent and extension release-pinned, while the extension resolves its key at runtime.
  newrelic_function_instrumentation = {
    for function_key, function_name in var.function_names : function_key => {
      function_name = function_name
      layers = [
        var.newrelic_java_slim_layer_arn,
        var.newrelic_extension_layer_arn
      ]
      environment = {
        NEW_RELIC_SERVERLESS_MODE                       = "true"
        NEW_RELIC_DISTRIBUTED_TRACING_ENABLED           = "true"
        NEW_RELIC_LAMBDA_EXTENSION_ENABLED              = "true"
        NEW_RELIC_LAMBDA_EXTENSION_LOGS_ENABLED         = "true"
        NEW_RELIC_APPLICATION_LOGGING_FORWARDING_ENABLED = "false"
        NEW_RELIC_LICENSE_KEY_SECRET                    = var.newrelic_ingest_secret_arn
      }
      # There is intentionally no forwarding Lambda/subscription filter. The extension is the
      # one configured New Relic log delivery path; CloudWatch remains Lambda's short-lived source log.
      log_forwarder                       = "newrelic-extension"
      cloudwatch_subscription_filter_arn  = ""
    }
  }
  newrelic_extension_secret_actions   = ["secretsmanager:GetSecretValue"]
  newrelic_extension_secret_resources = [var.newrelic_ingest_secret_arn]
}

# The deployment role receives only this explicit read permission for the extension's runtime
# resolution of the already-created ingest secret. No key value enters Terraform configuration.
data "aws_iam_policy_document" "newrelic_extension_secret_access" {
  statement {
    sid       = "ReadNewRelicIngestSecret"
    effect    = "Allow"
    actions   = local.newrelic_extension_secret_actions
    resources = local.newrelic_extension_secret_resources
  }
}
