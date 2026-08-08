package app.api;

import app.calendar.Calendar;
import app.calendar.CalendarService;
import app.calendar.CalendarTimeService;
import app.event.CalendarEvent;
import app.event.CalendarEventService;
import app.event.EventTimeInput;
import app.user.ApplicationUser;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
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

@Path("/calendars/{calendarId}/events")
@Produces(MediaType.APPLICATION_JSON)
public class EventApiResource {
    @Inject
    private ApiCaller apiCaller;

    @Inject
    private CalendarService calendarService;

    @Inject
    private CalendarEventService calendarEventService;

    @Inject
    private CalendarTimeService calendarTimeService;

    @Context
    private UriInfo uriInfo;

    @GET
    public JsonArray listEvents(@PathParam("calendarId") Long calendarId) {
        ApplicationUser actingUser = apiCaller.require();
        Calendar calendar = calendarService.requireMemberCalendar(actingUser, calendarId);
        JsonArrayBuilder events = Json.createArrayBuilder();
        for (CalendarEvent event : calendarEventService.findEventsForMember(actingUser, calendarId)) {
            events.add(ApiRepresentations.event(event, calendar, calendarTimeService));
        }
        return events.build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createEvent(
            @PathParam("calendarId") Long calendarId,
            @HeaderParam("If-Match") String ifMatch,
            JsonObject requestBody) {
        ApplicationUser actingUser = apiCaller.require();
        Calendar calendar = calendarService.requireMemberCalendar(actingUser, calendarId);
        EventTimeInput eventTime = ApiJson.requireEventTime(requestBody);
        CalendarEvent event = calendarEventService.createEvent(
                actingUser,
                calendarId,
                ApiJson.requireString(requestBody, "title"),
                ApiJson.optionalString(requestBody, "description"),
                ApiJson.optionalString(requestBody, "location"),
                eventTime,
                ApiPreconditions.requireCalendarVersion(ifMatch),
                calendar.getTimeZone());
        URI location = uriInfo.getAbsolutePathBuilder().path(event.getId().toString()).build();
        return Response.created(location)
                .tag(ApiPreconditions.eventEntityTag(event.getVersion(), calendar.getVersion()))
                .entity(ApiRepresentations.event(event, calendar, calendarTimeService))
                .build();
    }

    @GET
    @Path("/{eventId}")
    public Response getEvent(
            @PathParam("calendarId") Long calendarId,
            @PathParam("eventId") Long eventId) {
        ApplicationUser actingUser = apiCaller.require();
        CalendarEvent event = calendarEventService.requireEventForMember(actingUser, calendarId, eventId);
        Calendar calendar = calendarService.requireMemberCalendar(actingUser, calendarId);
        return eventResponse(event, calendar);
    }

    @PUT
    @Path("/{eventId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateEvent(
            @PathParam("calendarId") Long calendarId,
            @PathParam("eventId") Long eventId,
            @HeaderParam("If-Match") String ifMatch,
            JsonObject requestBody) {
        ApplicationUser actingUser = apiCaller.require();
        ApiPreconditions.EventVersions expectedVersions = ApiPreconditions.requireEventVersions(ifMatch);
        Calendar calendar = calendarService.requireMemberCalendar(actingUser, calendarId);
        CalendarEvent event = calendarEventService.updateEventInCalendar(
                actingUser,
                calendarId,
                eventId,
                expectedVersions.eventVersion(),
                ApiJson.requireString(requestBody, "title"),
                ApiJson.optionalString(requestBody, "description"),
                ApiJson.optionalString(requestBody, "location"),
                ApiJson.requireEventTime(requestBody),
                expectedVersions.calendarVersion(),
                calendar.getTimeZone());
        return eventResponse(event, calendar);
    }

    @DELETE
    @Path("/{eventId}")
    public Response deleteEvent(
            @PathParam("calendarId") Long calendarId,
            @PathParam("eventId") Long eventId,
            @HeaderParam("If-Match") String ifMatch) {
        ApplicationUser actingUser = apiCaller.require();
        ApiPreconditions.EventVersions expectedVersions = ApiPreconditions.requireEventVersions(ifMatch);
        Calendar calendar = calendarService.requireMemberCalendar(actingUser, calendarId);
        calendarEventService.deleteEventInCalendar(
                actingUser,
                calendarId,
                eventId,
                expectedVersions.eventVersion(),
                expectedVersions.calendarVersion(),
                calendar.getTimeZone());
        return Response.noContent().build();
    }

    private Response eventResponse(CalendarEvent event, Calendar calendar) {
        return Response.ok(ApiRepresentations.event(event, calendar, calendarTimeService))
                .tag(ApiPreconditions.eventEntityTag(event.getVersion(), calendar.getVersion()))
                .build();
    }
}
