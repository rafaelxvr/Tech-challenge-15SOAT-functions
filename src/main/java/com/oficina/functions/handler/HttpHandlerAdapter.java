package com.oficina.functions.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;

import java.util.Objects;

public final class HttpHandlerAdapter
        implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private final HttpHandlerOperation operation;

    public HttpHandlerAdapter(HttpHandlerOperation operation) {
        this.operation = Objects.requireNonNull(operation, "operation");
    }

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        return operation.handle(event, context);
    }
}
