package com.oficina.functions.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oficina.functions.auth.AutenticacaoException;
import com.oficina.functions.auth.CriarDesafio;
import com.oficina.functions.observability.JsonLogger;
import com.oficina.functions.observability.TraceContextAdapter;
import java.util.Iterator;
import java.util.Set;

/** HTTP API v2 entry point.  The source IP is taken only from API Gateway context. */
public final class CriarDesafioHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> FIELDS = Set.of("cpf");
    private final CriarDesafio criar;

    public CriarDesafioHandler() { this(FunctionFactoryHolder.challenge()); }
    public CriarDesafioHandler(CriarDesafio criar) { this.criar = criar; }

    @Override public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        String correlation = Correlation.id(event, context);
        try (var trace = TraceContextAdapter.extract(Correlation.traceparent(event))) {
            if (!Correlation.json(event) || event == null || Boolean.TRUE.equals(event.getIsBase64Encoded())
                    || event.getBody() == null || event.getBody().length() > 512)
                return HttpResponses.failure(400, correlation, "REQUISICAO_INVALIDA");
            JsonNode body = JSON.readTree(event.getBody());
            if (!body.isObject() || !exactFields(body, FIELDS) || !body.path("cpf").isTextual())
                return HttpResponses.failure(400, correlation, "REQUISICAO_INVALIDA");
            String source = event.getRequestContext() == null || event.getRequestContext().getHttp() == null
                    ? null : event.getRequestContext().getHttp().getSourceIp();
            if (source == null || source.isBlank()) return HttpResponses.failure(400, correlation, "REQUISICAO_INVALIDA");
            var issued = criar.executar(body.path("cpf").textValue(), source);
            var response = HttpResponses.success(202, correlation, new ChallengeData(issued.desafioId().toString(), issued.expiraEmSegundos()));
            JsonLogger.event("http_request_completed", correlation, TraceContextAdapter.current(), "challenge_accepted"); return response;
        } catch (AutenticacaoException exception) {
            JsonLogger.event("http_request_completed", correlation, TraceContextAdapter.current(), "challenge_rejected");
            return HttpResponses.failure(exception.status(), correlation, exception.codigo());
        } catch (Exception exception) {
            JsonLogger.event("http_request_completed", correlation, TraceContextAdapter.current(), "challenge_invalid");
            return HttpResponses.failure(400, correlation, "REQUISICAO_INVALIDA");
        }
    }

    private static boolean exactFields(JsonNode body, Set<String> fields) {
        Iterator<String> names = body.fieldNames();
        while (names.hasNext()) if (!fields.contains(names.next())) return false;
        return body.size() == fields.size();
    }
    private record ChallengeData(String desafioId, int expiraEmSegundos) { }
}
