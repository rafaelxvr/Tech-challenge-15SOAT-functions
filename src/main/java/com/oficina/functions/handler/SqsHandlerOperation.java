package com.oficina.functions.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;

@FunctionalInterface
public interface SqsHandlerOperation {

    SQSBatchResponse handle(SQSEvent event, Context context);
}
