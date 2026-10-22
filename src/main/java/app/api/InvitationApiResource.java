package app.api;

import app.calendar.Calendar;
import app.calendar.CalendarService;
import app.config.ApplicationUrlService;
import app.invitation.Invitation;
import app.invitation.InvitationService;
import app.invitation.InvitationSummary;
import app.membership.CalendarAccessService;
import app.membership.CalendarRole;
import app.user.ApplicationUser;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class InvitationApiResource {
    @Inject
    private ApiCaller apiCaller;

    @Inject
    private InvitationService invitationService;

    @Inject
    private CalendarService calendarService;

    @Inject
    private CalendarAccessService calendarAccessService;

    @Inject
    private ApplicationUrlService applicationUrlService;

    @POST
    @Path("registration-invitations")
    public Response createRegistrationInvitation() {
        Invitation invitation = invitationService.createRegistrationInvitation(apiCaller.require());
        return Response.status(Response.Status.CREATED)
                .entity(ApiRepresentations.invitation(invitation, applicationUrlService))
                .build();
    }

    @GET
    @Path("invitations")
    public JsonArray listInvitations() {
        JsonArrayBuilder invitations = Json.createArrayBuilder();
        for (InvitationSummary invitation : invitationService.listOutstandingInvitations(apiCaller.require())) {
            invitations.add(ApiRepresentations.invitation(invitation, applicationUrlService));
        }
        return invitations.build();
    }

    @DELETE
    @Path("invitations/{invitationId}")
    public Response revokeInvitation(@PathParam("invitationId") Long invitationId) {
        invitationService.revokeInvitation(apiCaller.require(), invitationId);
        return Response.noContent().build();
    }

    @POST
    @Path("invitation-acceptances")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response acceptCalendarInvitation(JsonObject requestBody) {
        ApplicationUser actingUser = apiCaller.require();
        Invitation invitation = invitationService.acceptInvitation(
                ApiJson.requireString(requestBody, "token"), actingUser);
        Long calendarId = invitation.getCalendar().getId();
        Calendar calendar = calendarService.requireMemberCalendar(actingUser, calendarId);
        CalendarRole role = calendarAccessService.findRole(actingUser, calendarId)
                .orElseThrow(() -> new IllegalStateException("Accepted calendar membership disappeared."));
        return Response.ok(ApiRepresentations.calendar(calendar, role, applicationUrlService))
                .tag(ApiPreconditions.calendarEntityTag(calendar.getVersion()))
                .build();
    }
}
