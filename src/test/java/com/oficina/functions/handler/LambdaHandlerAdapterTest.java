package com.oficina.functions.handler;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LambdaHandlerAdapterTest {

    @Test
    void adaptersExposeAwsConstructibleNoArgumentBoundary() throws Exception {
        HttpHandlerAdapter http = HttpHandlerAdapter.class.getConstructor().newInstance();
        SqsHandlerAdapter sqs = SqsHandlerAdapter.class.getConstructor().newInstance();

        assertThatThrownBy(() -> http.handleRequest(new APIGatewayV2HTTPEvent(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("F1/F4/F5 composition");
        assertThatThrownBy(() -> sqs.handleRequest(new SQSEvent(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("F1/F4/F5 composition");
    }

    @Test
    void httpAdapterDelegatesAwsEventToPlainOperation() {
        APIGatewayV2HTTPEvent event = new APIGatewayV2HTTPEvent();
        event.setBody("fixture");
        APIGatewayV2HTTPResponse expected = new APIGatewayV2HTTPResponse();
        expected.setStatusCode(202);
        AtomicReference<APIGatewayV2HTTPEvent> received = new AtomicReference<>();

        HttpHandlerAdapter adapter = new HttpHandlerAdapter((request, context) -> {
            received.set(request);
            return expected;
        });

        assertThat(adapter.handleRequest(event, null)).isSameAs(expected);
        assertThat(received).hasValue(event);
    }

    @Test
    void sqsAdapterDelegatesAwsBatchToPlainOperation() {
        SQSEvent event = new SQSEvent();
        event.setRecords(List.of(new SQSEvent.SQSMessage()));
        SQSBatchResponse expected = new SQSBatchResponse();
        expected.setBatchItemFailures(List.of());
        AtomicReference<SQSEvent> received = new AtomicReference<>();

        SqsHandlerAdapter adapter = new SqsHandlerAdapter((request, context) -> {
            received.set(request);
            return expected;
        });

        assertThat(adapter.handleRequest(event, null)).isSameAs(expected);
        assertThat(received).hasValue(event);
    }
}
