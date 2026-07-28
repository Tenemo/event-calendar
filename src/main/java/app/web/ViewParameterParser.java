package app.web;

import app.invitation.InvitationToken;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

public final class ViewParameterParser {
    private ViewParameterParser() {
    }

    public static OptionalLong positiveLong(String value) {
        if (value == null
                || value.isBlank()
                || value.length() > 19
                || !value.chars().allMatch(Character::isDigit)) {
            return OptionalLong.empty();
        }
        try {
            long parsedValue = Long.parseLong(value);
            return parsedValue > 0
                    ? OptionalLong.of(parsedValue)
                    : OptionalLong.empty();
        } catch (NumberFormatException exception) {
            return OptionalLong.empty();
        }
    }

    public static Optional<String> invitationToken(
            Map<String, String[]> requestParameters,
            boolean acceptSubmittedComponentParameterNames) {
        if (requestParameters == null || requestParameters.isEmpty()) {
            return Optional.empty();
        }

        String validatedInvitationToken = null;
        int invitationTokenValueCount = 0;
        for (Map.Entry<String, String[]> requestParameter : requestParameters.entrySet()) {
            if (!isInvitationTokenParameter(
                    requestParameter.getKey(), acceptSubmittedComponentParameterNames)) {
                continue;
            }
            String[] submittedValues = requestParameter.getValue();
            if (submittedValues == null || submittedValues.length != 1) {
                return Optional.empty();
            }
            invitationTokenValueCount++;
            if (invitationTokenValueCount != 1) {
                return Optional.empty();
            }
            String normalizedInvitationToken = InvitationToken.normalize(submittedValues[0]);
            if (!InvitationToken.isValidCandidate(normalizedInvitationToken)) {
                return Optional.empty();
            }
            validatedInvitationToken = normalizedInvitationToken;
        }
        return invitationTokenValueCount == 1
                ? Optional.of(validatedInvitationToken)
                : Optional.empty();
    }

    private static boolean isInvitationTokenParameter(
            String parameterName,
            boolean acceptSubmittedComponentParameterNames) {
        return "token".equals(parameterName)
                || (acceptSubmittedComponentParameterNames
                        && ("invitationToken".equals(parameterName)
                                || (parameterName != null
                                        && parameterName.endsWith(":invitationToken"))));
    }
}
