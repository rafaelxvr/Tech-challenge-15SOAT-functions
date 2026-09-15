package com.oficina.functions.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import java.util.Map;
import java.util.UUID;

final class Correlation {
    private Correlation() { }
    static String id(APIGatewayV2HTTPEvent event, Context context) {
        if (event != null && event.getHeaders() != null) {
            String value = event.getHeaders().get("x-correlation-id");
            if (value != null && value.matches("[A-Za-z0-9_-]{1,64}")) return value;
        }
        if (context != null && context.getAwsRequestId() != null) return context.getAwsRequestId();
        return UUID.randomUUID().toString();
    }
    static boolean json(APIGatewayV2HTTPEvent event) {
        if (event == null || event.getHeaders() == null) return false;
        for (Map.Entry<String, String> header : event.getHeaders().entrySet())
            if ("content-type".equalsIgnoreCase(header.getKey())) return header.getValue() != null && header.getValue().toLowerCase().startsWith("application/json");
        return false;
    }
}
