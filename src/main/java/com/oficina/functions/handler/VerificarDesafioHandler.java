package com.oficina.functions.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oficina.functions.auth.AutenticacaoException;
import com.oficina.functions.auth.VerificarDesafio;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

public final class VerificarDesafioHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> FIELDS = Set.of("desafioId", "codigo");
    private final VerificarDesafio verificar;
    public VerificarDesafioHandler() { this(FunctionFactoryHolder.factory().verificarDesafio()); }
    public VerificarDesafioHandler(VerificarDesafio verificar) { this.verificar = verificar; }

    @Override public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        String correlation = Correlation.id(event, context);
        try {
            if (!Correlation.json(event) || event == null || Boolean.TRUE.equals(event.getIsBase64Encoded())
                    || event.getBody() == null || event.getBody().length() > 512)
                return HttpResponses.failure(400, correlation, "REQUISICAO_INVALIDA");
            JsonNode body = JSON.readTree(event.getBody());
            if (!body.isObject() || !exactFields(body) || !body.path("desafioId").isTextual() || !body.path("codigo").isTextual())
                return HttpResponses.failure(400, correlation, "REQUISICAO_INVALIDA");
            var token = verificar.executar(UUID.fromString(body.path("desafioId").textValue()), body.path("codigo").textValue());
            return HttpResponses.success(200, correlation, token);
        } catch (AutenticacaoException exception) {
            return HttpResponses.failure(exception.status(), correlation, exception.codigo());
        } catch (Exception exception) {
            return HttpResponses.failure(400, correlation, "REQUISICAO_INVALIDA");
        }
    }
    private static boolean exactFields(JsonNode body) {
        Iterator<String> names = body.fieldNames();
        while (names.hasNext()) if (!FIELDS.contains(names.next())) return false;
        return body.size() == FIELDS.size();
    }
}
