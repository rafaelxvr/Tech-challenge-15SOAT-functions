package com.oficina.functions.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;

import java.util.Objects;

public final class SqsHandlerAdapter implements RequestHandler<SQSEvent, SQSBatchResponse> {

    private final SqsHandlerOperation operation;

    public SqsHandlerAdapter(SqsHandlerOperation operation) {
        this.operation = Objects.requireNonNull(operation, "operation");
    }

    @Override
    public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
        return operation.handle(event, context);
    }
}
