locals {
  auth_routes = {
    "POST /api/auth/cpf/desafios"  = "challenge"
    "POST /api/auth/cpf/verificar" = "verification"
  }
}
resource "aws_apigatewayv2_authorizer" "request" {
  api_id                            = var.gateway.api_id
  name                              = "${local.prefix}-request"
  authorizer_type                   = "REQUEST"
  authorizer_uri                    = aws_lambda_function.function["authorizer"].invoke_arn
  authorizer_payload_format_version = "2.0"
  enable_simple_responses           = true
  authorizer_result_ttl_in_seconds  = 0
  identity_sources                  = []
}
resource "aws_lambda_permission" "authorizer" {
  statement_id   = "ExactHttpApiAuthorizer"
  action         = "lambda:InvokeFunction"
  function_name  = aws_lambda_function.function["authorizer"].function_name
  principal      = "apigateway.amazonaws.com"
  source_account = var.account_id
  source_arn     = "${var.gateway.execution_arn}/authorizers/${aws_apigatewayv2_authorizer.request.id}"
}
resource "aws_apigatewayv2_integration" "auth" {
  for_each               = local.auth_routes
  api_id                 = var.gateway.api_id
  integration_type       = "AWS_PROXY"
  integration_method     = "POST"
  integration_uri        = aws_lambda_function.function[each.value].invoke_arn
  payload_format_version = "2.0"
  timeout_milliseconds   = 20000
}
resource "aws_apigatewayv2_route" "auth" {
  for_each           = local.auth_routes
  api_id             = var.gateway.api_id
  route_key          = each.key
  authorization_type = "NONE"
  target             = "integrations/${aws_apigatewayv2_integration.auth[each.key].id}"
}
resource "aws_lambda_permission" "auth" {
  for_each       = local.auth_routes
  statement_id   = "ExactHttpApiAuthRoute"
  action         = "lambda:InvokeFunction"
  function_name  = aws_lambda_function.function[each.value].function_name
  principal      = "apigateway.amazonaws.com"
  source_account = var.account_id
  source_arn     = "${var.gateway.execution_arn}/${var.gateway.stage_name}/${replace(each.key, "POST /", "POST/")}"
}
