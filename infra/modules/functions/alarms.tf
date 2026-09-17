locals {
  prefix = "${var.name}-${var.environment}"
  tags = {
    project     = "oficina-phase3"
    environment = var.environment
    managedBy   = "oficina-functions"
    component   = "native-alarms"
  }
}

resource "aws_sns_topic" "native_alarms" {
  name              = "${local.prefix}-native-alarms"
  kms_master_key_id = "alias/aws/sns"
  tags              = local.tags
}

resource "aws_sns_topic_subscription" "operator" {
  topic_arn = aws_sns_topic.native_alarms.arn
  protocol  = "email"
  endpoint  = var.operator_email
}

# No treat_missing_data shortcut: an absent source metric is unknown, never proof of healthy delivery.
resource "aws_cloudwatch_metric_alarm" "notification_source_age" {
  alarm_name          = "${local.prefix}-notification-source-age"
  alarm_description   = "Notification FIFO source queue oldest-message age exceeds five minutes for two one-minute periods."
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateAgeOfOldestMessage"
  statistic           = "Maximum"
  comparison_operator = "GreaterThanThreshold"
  threshold           = 300
  period              = 60
  evaluation_periods  = 2
  datapoints_to_alarm = 2
  treat_missing_data  = "missing"
  dimensions          = { QueueName = var.notification_queue_name }
  alarm_actions       = [aws_sns_topic.native_alarms.arn]
  ok_actions          = [aws_sns_topic.native_alarms.arn]
  tags                = local.tags
}

resource "aws_cloudwatch_metric_alarm" "notification_dlq_depth" {
  alarm_name          = "${local.prefix}-notification-dlq-depth"
  alarm_description   = "Any message in this environment's notification DLQ requires inspected recovery."
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  statistic           = "Maximum"
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  period              = 60
  evaluation_periods  = 1
  datapoints_to_alarm = 1
  treat_missing_data  = "missing"
  dimensions          = { QueueName = var.notification_dlq_name }
  alarm_actions       = [aws_sns_topic.native_alarms.arn]
  tags                = local.tags
}

resource "aws_cloudwatch_metric_alarm" "lambda_throttles" {
  for_each            = var.function_names
  alarm_name          = "${local.prefix}-${each.key}-throttles"
  alarm_description   = "Any throttle for the exact ${each.key} Lambda requires operator investigation."
  namespace           = "AWS/Lambda"
  metric_name         = "Throttles"
  statistic           = "Sum"
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  period              = 60
  evaluation_periods  = 1
  datapoints_to_alarm = 1
  treat_missing_data  = "missing"
  dimensions          = { FunctionName = each.value }
  alarm_actions       = [aws_sns_topic.native_alarms.arn]
  tags                = local.tags
}
