package app.api;

import app.membership.CalendarMembership;
import app.membership.CalendarMembershipService;
import app.membership.CalendarRole;
import app.util.ValidationException;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/calendars/{calendarId}/members")
@Produces(MediaType.APPLICATION_JSON)
public class MembershipApiResource {
    @Inject
    private ApiCaller apiCaller;

    @Inject
    private CalendarMembershipService calendarMembershipService;

    @GET
    public JsonArray listMembers(@PathParam("calendarId") Long calendarId) {
        JsonArrayBuilder members = Json.createArrayBuilder();
        for (CalendarMembership membership : calendarMembershipService.listMembers(
                apiCaller.require(), calendarId)) {
            members.add(ApiRepresentations.membership(membership));
        }
        return members.build();
    }

    @PUT
    @Path("/{userId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public JsonObject changeMemberRole(
            @PathParam("calendarId") Long calendarId,
            @PathParam("userId") Long userId,
            JsonObject requestBody) {
        CalendarMembership membership = calendarMembershipService.changeMemberRole(
                apiCaller.require(),
                calendarId,
                userId,
                requireRole(ApiJson.requireString(requestBody, "role")));
        return ApiRepresentations.membership(membership);
    }

    @DELETE
    @Path("/{userId}")
    public Response removeMember(
            @PathParam("calendarId") Long calendarId,
            @PathParam("userId") Long userId) {
        calendarMembershipService.removeMembership(apiCaller.require(), calendarId, userId);
        return Response.noContent().build();
    }

    private static CalendarRole requireRole(String roleName) {
        try {
            return CalendarRole.valueOf(roleName);
        } catch (IllegalArgumentException exception) {
            throw new ValidationException("role must be EDITOR or ADMIN.");
        }
    }
}
