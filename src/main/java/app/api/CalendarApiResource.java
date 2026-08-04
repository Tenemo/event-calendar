package app.api;

import app.calendar.Calendar;
import app.calendar.CalendarMembershipSummary;
import app.calendar.CalendarService;
import app.config.ApplicationUrlService;
import app.invitation.Invitation;
import app.invitation.InvitationService;
import app.membership.CalendarAccessService;
import app.membership.CalendarRole;
import app.user.ApplicationUser;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.net.URI;

@Path("/calendars")
@Produces(MediaType.APPLICATION_JSON)
public class CalendarApiResource {
    @Inject
    private ApiCaller apiCaller;

    @Inject
    private CalendarService calendarService;

    @Inject
    private CalendarAccessService calendarAccessService;

    @Inject
    private InvitationService invitationService;

    @Inject
    private ApplicationUrlService applicationUrlService;

    @Context
    private UriInfo uriInfo;

    @GET
    public JsonArray listCalendars() {
        JsonArrayBuilder calendars = Json.createArrayBuilder();
        for (CalendarMembershipSummary calendar : calendarService.findCalendarsForUser(apiCaller.require())) {
            calendars.add(ApiRepresentations.calendarSummary(calendar, applicationUrlService));
        }
        return calendars.build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createCalendar(JsonObject requestBody) {
        ApplicationUser actingUser = apiCaller.require();
        Calendar calendar = calendarService.createCalendar(
                actingUser,
                ApiJson.requireString(requestBody, "name"));
        URI location = uriInfo.getAbsolutePathBuilder().path(calendar.getId().toString()).build();
        return Response.created(location)
                .tag(ApiPreconditions.calendarEntityTag(calendar.getVersion()))
                .entity(ApiRepresentations.calendar(calendar, CalendarRole.ADMIN, applicationUrlService))
                .build();
    }

    @GET
    @Path("/{calendarId}")
    public Response getCalendar(@PathParam("calendarId") Long calendarId) {
        ApplicationUser actingUser = apiCaller.require();
        Calendar calendar = calendarService.requireMemberCalendar(actingUser, calendarId);
        return calendarResponse(calendar, requireRole(actingUser, calendarId));
    }

    @PUT
    @Path("/{calendarId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateCalendar(
            @PathParam("calendarId") Long calendarId,
            @HeaderParam("If-Match") String ifMatch,
            JsonObject requestBody) {
        ApplicationUser actingUser = apiCaller.require();
        Calendar calendar = calendarService.updateCalendarSettings(
                actingUser,
                calendarId,
                ApiJson.requireString(requestBody, "name"),
                ApiJson.optionalString(requestBody, "description"),
                ApiJson.requireString(requestBody, "timeZone"),
                ApiJson.requireBoolean(requestBody, "publicAccessEnabled"),
                ApiPreconditions.requireCalendarVersion(ifMatch));
        return calendarResponse(calendar, CalendarRole.ADMIN);
    }

    @POST
    @Path("/{calendarId}/calendar-link-regenerations")
    public Response regenerateCalendarLink(
            @PathParam("calendarId") Long calendarId,
            @HeaderParam("If-Match") String ifMatch) {
        Calendar calendar = calendarService.regenerateCalendarLink(
                apiCaller.require(),
                calendarId,
                ApiPreconditions.requireCalendarVersion(ifMatch));
        return calendarResponse(calendar, CalendarRole.ADMIN);
    }

    @POST
    @Path("/{calendarId}/editor-invitations")
    public Response createEditorInvitation(@PathParam("calendarId") Long calendarId) {
        Invitation invitation = invitationService.createCalendarEditorInvitation(
                apiCaller.require(), calendarId);
        return Response.status(Response.Status.CREATED)
                .entity(ApiRepresentations.invitation(invitation, applicationUrlService))
                .build();
    }

    private Response calendarResponse(Calendar calendar, CalendarRole role) {
        return Response.ok(ApiRepresentations.calendar(calendar, role, applicationUrlService))
                .tag(ApiPreconditions.calendarEntityTag(calendar.getVersion()))
                .build();
    }

    private CalendarRole requireRole(ApplicationUser actingUser, Long calendarId) {
        return calendarAccessService.findRole(actingUser, calendarId)
                .orElseThrow(() -> new IllegalStateException("Required calendar membership disappeared."));
    }
}
