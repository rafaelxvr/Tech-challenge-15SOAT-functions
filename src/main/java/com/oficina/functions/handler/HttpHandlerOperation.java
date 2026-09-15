package com.oficina.functions.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;

@FunctionalInterface
public interface HttpHandlerOperation {

    APIGatewayV2HTTPResponse handle(APIGatewayV2HTTPEvent event, Context context);
}
