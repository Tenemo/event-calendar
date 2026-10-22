package app.api;

import jakarta.json.Json;
import jakarta.json.JsonObjectBuilder;
import jakarta.ws.rs.core.Response;
import java.net.URI;

final class ApiProblemResponses {
    static final String PROBLEM_JSON = "application/problem+json";
    private static final String BEARER_CHALLENGE = "Bearer realm=\"calendar.social\"";

    private ApiProblemResponses() {
    }

    static Response unauthorized(URI requestUri) {
        return Response.fromResponse(problem(
                        401,
                        "Unauthorized",
                        "A valid API bearer token is required.",
                        requestUri))
                .header("WWW-Authenticate", BEARER_CHALLENGE)
                .build();
    }

    static Response problem(int status, String title, String detail, URI requestUri) {
        JsonObjectBuilder body = Json.createObjectBuilder()
                .add("type", "about:blank")
                .add("title", title)
                .add("status", status)
                .add("detail", detail);
        if (requestUri != null) {
            body.add("instance", requestUri.getPath());
        }
        return Response.status(status)
                .type(PROBLEM_JSON)
                .entity(body.build())
                .build();
    }
}
