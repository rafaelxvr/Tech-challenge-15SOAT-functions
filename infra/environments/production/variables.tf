variable "name" {
  type = string
  validation {
    condition     = var.name == "oficina-phase3"
    error_message = "The reviewed Phase 3 functions name is fixed to oficina-phase3."
  }
}

variable "environment" {
  type = string
  validation {
    condition     = var.environment == "production"
    error_message = "environment must be staging or production."
  }
}

variable "aws_region" {
  type = string
  validation {
    condition     = var.aws_region == "us-east-1"
    error_message = "The reviewed functions deployment is limited to us-east-1."
  }
}

variable "network" {
  type = object({
    private_subnet_ids         = set(string)
    function_security_group_id = string
  })
  description = "Allowlisted private Lambda network references from the platform/foundation output artifact."
  validation {
    condition     = length(var.network.private_subnet_ids) > 0 && can(regex("^sg-[a-zA-Z0-9]+$", var.network.function_security_group_id))
    error_message = "Functions require reviewed private subnets and one reviewed function security group."
  }
}

variable "lambda_artifact" {
  type = object({
    s3_bucket         = string
    s3_key            = string
    s3_object_version = string
    sha256_base64     = string
    sha256_hex        = string
  })
  description = "The immutable, reviewed shaded FUN JAR. Both encodings are required so deployment verifies the artifact identity."
  validation {
    condition = (
      can(regex("^[0-9a-fA-F]{64}$", var.lambda_artifact.sha256_hex)) && can(regex("^[A-Za-z0-9+/]{43}=$", var.lambda_artifact.sha256_base64)) &&
      length(trimspace(var.lambda_artifact.s3_bucket)) > 0 && length(trimspace(var.lambda_artifact.s3_key)) > 0 && length(trimspace(var.lambda_artifact.s3_object_version)) > 0
    )
    error_message = "lambda_artifact must be an immutable S3 object version with SHA-256 in hex and base64 encodings."
  }
}

variable "runtime_secret_arns" {
  type = object({
    auth_lookup          = string
    notification_lookup  = string
    customer_signing_key = string
    authorizer_trust     = string
    rds_ca_certificate   = string
  })
  description = "Secret ARNs only. Secret values are initialized by the private deployment job after Terraform and never enter state."
  validation {
    condition = (
      alltrue([for arn in values(var.runtime_secret_arns) : can(regex("^arn:aws:secretsmanager:us-east-1:${var.account_id}:secret:oficina/${var.environment}/[A-Za-z0-9/_+=.@-]+$", arn))]) &&
      length(toset(values(var.runtime_secret_arns))) == length(values(var.runtime_secret_arns))
    )
    error_message = "Each runtime secret must be a distinct us-east-1 Secrets Manager ARN; shared credential bundles are forbidden."
  }
}

variable "customer_key_id" {
  type        = string
  description = "Reviewed public customer signing key identifier; it is not secret material."
  validation {
    condition     = can(regex("^[A-Za-z0-9_-]{1,64}$", var.customer_key_id))
    error_message = "customer_key_id must be a bounded key identifier."
  }
}

variable "staff_key_id" {
  type        = string
  description = "Reviewed staff HMAC verification key identifier; it is not secret material."
  validation {
    condition     = can(regex("^[A-Za-z0-9_-]{1,64}$", var.staff_key_id))
    error_message = "staff_key_id must be a bounded key identifier."
  }
}

variable "ses_sender_email" {
  type        = string
  description = "Pre-verified SES sandbox sender; recipient verification remains an external release prerequisite."
  validation {
    condition     = can(regex("^[^@[:space:]]+@[^@[:space:]]+\\.[^@[:space:]]+$", var.ses_sender_email))
    error_message = "ses_sender_email must be a concrete verified sender address."
  }
}

variable "ses_sandbox_mode" {
  type        = bool
  description = "The study account remains in SES sandbox until R4 documents a verified production-access change."
  validation {
    condition     = var.ses_sandbox_mode
    error_message = "I5 supports the reviewed SES sandbox only."
  }
}

variable "approved_secret_count" {
  type        = number
  description = "The reviewed cross-repository secret inventory count; I5 may not create or hide additional secrets."
  validation {
    condition     = var.approved_secret_count == 16
    error_message = "The approved Phase 3 account inventory is exactly 16 secrets; reconcile inventory before deployment."
  }
}

variable "planned_monthly_invocations" {
  type = object({
    challenge    = number
    verification = number
    authorizer   = number
    notification = number
  })
  description = "Measured R4 workload forecast used to keep the four 1 GiB/20-second handlers inside the approved 200,000 GB-second study envelope."
  validation {
    condition     = alltrue([for count in values(var.planned_monthly_invocations) : count >= 0 && floor(count) == count])
    error_message = "planned_monthly_invocations values must be non-negative whole invocation counts."
  }
}

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
    condition     = can(regex("^arn:aws:lambda:us-east-1:451483290750:layer:NewRelicLambdaExtension:[1-9][0-9]*$", var.newrelic_extension_layer_arn))
    error_message = "newrelic_extension_layer_arn must be a pinned official New Relic extension ARN in us-east-1."
  }
}


variable "ownership_handoff_reviewed" {
  type    = bool
  default = false
}

variable "customer_public_keys" {
  type        = map(object({ kty = string, alg = string, use = string, n = string, e = string }))
  description = "Reviewed public RSA JWK components indexed by kid; no private fields. Must match the deployed signing key and overlap rotation set."
  validation {
    condition     = contains(keys(var.customer_public_keys), var.customer_key_id) && length(var.customer_public_keys) <= 3 && alltrue([for kid, key in var.customer_public_keys : can(regex("^[A-Za-z0-9_-]{1,64}$", kid)) && key.kty == "RSA" && key.alg == "RS256" && key.use == "sig" && key.e == "AQAB" && can(regex("^[A-Za-z0-9_-]{342,684}$", key.n))])
    error_message = "Publish only bounded RSA/RS256 public JWKs, including the current customer kid, with exponent 65537."
  }
}
