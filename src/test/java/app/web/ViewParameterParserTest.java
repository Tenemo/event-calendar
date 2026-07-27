package app.web;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.invitation.InvitationToken;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ViewParameterParserTest {
    @Test
    void acceptsPositiveLongsAndRejectsMalformedOrOutOfRangeValues() {
        assertEquals(42L, ViewParameterParser.positiveLong("42").orElseThrow());

        assertAll(
                () -> assertTrue(ViewParameterParser.positiveLong(null).isEmpty()),
                () -> assertTrue(ViewParameterParser.positiveLong("").isEmpty()),
                () -> assertTrue(ViewParameterParser.positiveLong(" 42 ").isEmpty()),
                () -> assertTrue(ViewParameterParser.positiveLong("+42").isEmpty()),
                () -> assertTrue(ViewParameterParser.positiveLong("-1").isEmpty()),
                () -> assertTrue(ViewParameterParser.positiveLong("0").isEmpty()),
                () -> assertTrue(ViewParameterParser.positiveLong("42.0").isEmpty()),
                () -> assertTrue(
                        ViewParameterParser.positiveLong("9999999999999999999").isEmpty()));
    }

    @Test
    void acceptsExactlyOneBoundedInvitationTokenAcrossExplicitlyEnabledParameterNames() {
        String invitationToken = "a".repeat(InvitationToken.MAXIMUM_LENGTH);

        assertAll(
                () -> assertEquals(
                        invitationToken,
                        ViewParameterParser.invitationToken(
                                        Map.of("token", new String[] {"  " + invitationToken + "  "}),
                                        false,
                                        false)
                                .orElseThrow()),
                () -> assertEquals(
                        invitationToken,
                        ViewParameterParser.invitationToken(
                                        Map.of("registrationForm:invitationToken", new String[] {invitationToken}),
                                        false,
                                        true)
                                .orElseThrow()),
                () -> assertEquals(
                        invitationToken,
                        ViewParameterParser.invitationToken(
                                        Map.of("invitationToken", new String[] {invitationToken}),
                                        false,
                                        true)
                                .orElseThrow()),
                () -> assertEquals(
                        invitationToken,
                        ViewParameterParser.invitationToken(
                                        Map.of("invite", new String[] {invitationToken}),
                                        true,
                                        false)
                                .orElseThrow()));
    }

    @Test
    void rejectsMissingDuplicateConflictingMalformedAndUnexpectedInvitationParameters() {
        String firstInvitationToken = "a".repeat(43);
        String secondInvitationToken = "b".repeat(43);

        assertAll(
                () -> assertTrue(ViewParameterParser.invitationToken(
                                Map.of(), false, false)
                        .isEmpty()),
                () -> assertTrue(ViewParameterParser.invitationToken(
                                Map.of("token", new String[0]), false, false)
                        .isEmpty()),
                () -> assertTrue(ViewParameterParser.invitationToken(
                                Map.of("token", new String[] {firstInvitationToken, secondInvitationToken}),
                                false,
                                false)
                        .isEmpty()),
                () -> assertTrue(ViewParameterParser.invitationToken(
                                Map.of(
                                        "token", new String[] {firstInvitationToken},
                                        "form:invitationToken", new String[] {secondInvitationToken}),
                                false,
                                true)
                        .isEmpty()),
                () -> assertTrue(ViewParameterParser.invitationToken(
                                Map.of(
                                        "token", new String[] {firstInvitationToken},
                                        "form:invitationToken", new String[] {firstInvitationToken}),
                                false,
                                true)
                        .isEmpty()),
                () -> assertTrue(ViewParameterParser.invitationToken(
                                Map.of("token", new String[] {"x".repeat(InvitationToken.MAXIMUM_LENGTH + 1)}),
                                false,
                                false)
                        .isEmpty()),
                () -> assertTrue(ViewParameterParser.invitationToken(
                                Map.of("token", new String[] {"token\r\nvalue"}),
                                false,
                                false)
                        .isEmpty()),
                () -> assertTrue(ViewParameterParser.invitationToken(
                                Map.of(
                                        "registrationForm:invitationToken",
                                        new String[] {"x".repeat(InvitationToken.MAXIMUM_LENGTH + 1)}),
                                false,
                                true)
                        .isEmpty()),
                () -> assertTrue(ViewParameterParser.invitationToken(
                                Map.of(
                                        "registrationForm:invitationToken:unexpected",
                                        new String[] {firstInvitationToken}),
                                false,
                                true)
                        .isEmpty()),
                () -> assertTrue(ViewParameterParser.invitationToken(
                                Map.of("invite", new String[] {firstInvitationToken}),
                                false,
                                false)
                        .isEmpty()),
                () -> assertTrue(ViewParameterParser.invitationToken(
                                Map.of("otherToken", new String[] {firstInvitationToken}),
                                false,
                                false)
                        .isEmpty()));
    }

    @Test
    void submittedComponentParameterNamesAreRejectedOutsidePostbackParsing() {
        String invitationToken = "a".repeat(43);

        assertAll(
                () -> assertTrue(ViewParameterParser.invitationToken(
                                Map.of("invitationToken", new String[] {invitationToken}),
                                false,
                                false)
                        .isEmpty()),
                () -> assertTrue(ViewParameterParser.invitationToken(
                                Map.of(
                                        "registrationForm:invitationToken",
                                        new String[] {invitationToken}),
                                false,
                                false)
                        .isEmpty()));
    }
}
