package app.api;

import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/me")
@Produces(MediaType.APPLICATION_JSON)
public class CallerApiResource {
    @Inject
    private ApiCaller apiCaller;

    @GET
    public JsonObject getCaller() {
        return ApiRepresentations.user(apiCaller.require());
    }
}
