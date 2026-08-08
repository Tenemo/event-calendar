package app.api;

import app.util.AuthorizationException;
import app.util.ConflictException;
import app.util.NotFoundException;
import app.util.ValidationException;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotAcceptableException;
import jakarta.ws.rs.NotAllowedException;
import jakarta.ws.rs.NotSupportedException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class ApiExceptionMapper implements ExceptionMapper<RuntimeException> {
    private static final System.Logger LOGGER = System.getLogger(ApiExceptionMapper.class.getName());

    @Context
    private UriInfo uriInfo;

    @Override
    public Response toResponse(RuntimeException exception) {
        if (exception instanceof ApiRequestException requestException) {
            return problem(requestException.status(), requestException.title(), requestException.getMessage());
        }
        if (exception instanceof ValidationException) {
            return problem(422, "Validation failed", exception.getMessage());
        }
        if (exception instanceof AuthorizationException) {
            return problem(403, "Forbidden", exception.getMessage());
        }
        if (exception instanceof NotFoundException) {
            return problem(404, "Not found", exception.getMessage());
        }
        if (exception instanceof ConflictException) {
            return problem(412, "Precondition failed", exception.getMessage());
        }
        if (exception instanceof BadRequestException) {
            return problem(400, "Bad request", "The request could not be parsed.");
        }
        if (exception instanceof NotAllowedException) {
            return problem(405, "Method not allowed", "The resource does not support this HTTP method.");
        }
        if (exception instanceof NotSupportedException) {
            return problem(415, "Unsupported media type", "Use application/json for request bodies.");
        }
        if (exception instanceof NotAcceptableException) {
            return problem(406, "Not acceptable", "The requested response format is unavailable.");
        }
        if (exception instanceof WebApplicationException webApplicationException) {
            int status = webApplicationException.getResponse().getStatus();
            return problem(status, Response.Status.fromStatusCode(status) == null
                    ? "Request failed"
                    : Response.Status.fromStatusCode(status).getReasonPhrase(),
                    "The request could not be completed.");
        }

        LOGGER.log(System.Logger.Level.ERROR, "Unhandled API request failure.", exception);
        return problem(500, "Internal server error", "The request could not be completed.");
    }

    private Response problem(int status, String title, String detail) {
        return ApiProblemResponses.problem(
                status,
                title,
                detail,
                uriInfo == null ? null : uriInfo.getRequestUri());
    }
}
