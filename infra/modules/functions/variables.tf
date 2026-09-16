variable "name" {
  type = string
  validation {
    condition     = var.name == "oficina-phase3"
    error_message = "The approved Phase 3 prefix is oficina-phase3."
  }
}

variable "environment" {
  type = string
  validation {
    condition     = contains(["staging", "production"], var.environment)
    error_message = "environment must be staging or production."
  }
}

variable "notification_function_name" {
  type        = string
  description = "Exact environment notification Lambda name supplied from the reviewed function output."
  validation {
    condition     = can(regex("^oficina-phase3-(staging|production)-notification$", var.notification_function_name))
    error_message = "notification_function_name must be an exact approved environment function."
  }
}

variable "notification_queue_name" {
  type        = string
  description = "Exact environment FIFO source queue name supplied from the reviewed function output."
  validation {
    condition     = can(regex("^oficina-phase3-(staging|production)-notifications\\.fifo$", var.notification_queue_name))
    error_message = "notification_queue_name must be an exact approved environment source queue."
  }
}

variable "notification_dlq_name" {
  type        = string
  description = "Exact environment FIFO DLQ name supplied from the reviewed function output."
  validation {
    condition     = can(regex("^oficina-phase3-(staging|production)-notifications-dlq\\.fifo$", var.notification_dlq_name))
    error_message = "notification_dlq_name must be an exact approved environment DLQ."
  }
}

variable "function_names" {
  type = map(string)
  validation {
    condition = (
      toset(keys(var.function_names)) == toset(["challenge", "verification", "authorizer", "notification"]) &&
      alltrue([for key, name in var.function_names : name == "oficina-phase3-${var.environment}-${key}"])
    )
    error_message = "Native alarms require the four exact functions in this environment."
  }
}

variable "operator_email" {
  type        = string
  description = "Pre-verified operator subscription address. Confirmation remains an R4 operator action."
  validation {
    condition     = can(regex("^[^@[:space:]]+@[^@[:space:]]+\\.[^@[:space:]]+$", var.operator_email))
    error_message = "operator_email must be a concrete operator address."
  }
}

variable "newrelic_java_slim_layer_arn" {
  type        = string
  description = "Reviewed immutable New Relic Java 17 slim layer ARN. The numeric layer version pins the artifact."
  validation {
    condition     = can(regex("^arn:aws:lambda:us-east-1:451483290750:layer:NewRelicJava17:[1-9][0-9]*$", var.newrelic_java_slim_layer_arn))
    error_message = "newrelic_java_slim_layer_arn must be a pinned official Java 17 New Relic layer ARN in us-east-1."
  }
}

variable "newrelic_extension_layer_arn" {
  type        = string
  description = "Reviewed immutable New Relic extension layer ARN. The numeric layer version pins the artifact."
  validation {
    condition     = can(regex("^arn:aws:lambda:us-east-1:451483290750:layer:NewRelicExtension:[1-9][0-9]*$", var.newrelic_extension_layer_arn))
    error_message = "newrelic_extension_layer_arn must be a pinned official New Relic extension ARN in us-east-1."
  }
}

variable "newrelic_ingest_secret_arn" {
  type        = string
  description = "Existing environment-scoped New Relic ingest key secret resolved by the extension at runtime."
  validation {
    condition     = can(regex("^arn:aws:secretsmanager:us-east-1:[0-9]{12}:secret:oficina/${var.environment}/newrelic-ingest-[A-Za-z0-9/_+=.@-]+$", var.newrelic_ingest_secret_arn))
    error_message = "newrelic_ingest_secret_arn must reference the approved existing environment ingest secret."
  }
}
