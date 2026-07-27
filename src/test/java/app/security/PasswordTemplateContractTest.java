package app.security;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

final class PasswordTemplateContractTest {
    private static final Path WEB_APPLICATION_ROOT = Path.of("src", "main", "webapp");
    private static final String TRANSPORT_MAXIMUM_LENGTH_ATTRIBUTE =
            "maxlength=\"#{passwordService.maximumPasswordTransportLength}\"";

    @Test
    void everyPasswordInputUsesTheSharedUtf16TransportLimit() throws IOException {
        String loginTemplate = Files.readString(WEB_APPLICATION_ROOT.resolve("login.xhtml"));
        String registrationTemplate = Files.readString(
                WEB_APPLICATION_ROOT.resolve("register.xhtml"));
        String accountSettingsTemplate = Files.readString(
                WEB_APPLICATION_ROOT.resolve(Path.of("app", "account-settings.xhtml")));

        assertAll(
                () -> assertEquals(
                        PasswordService.MAXIMUM_PASSWORD_LENGTH * 2,
                        PasswordService.MAXIMUM_PASSWORD_TRANSPORT_LENGTH),
                () -> assertEquals(1_024, PasswordService.MAXIMUM_PASSWORD_TRANSPORT_LENGTH),
                () -> assertTrue(elementHasAttributes(
                        loginTemplate,
                        "h:inputSecret",
                        "id=\"password\"",
                        TRANSPORT_MAXIMUM_LENGTH_ATTRIBUTE)),
                () -> assertTrue(elementHasAttributes(
                        registrationTemplate,
                        "p:password",
                        "id=\"password\"",
                        TRANSPORT_MAXIMUM_LENGTH_ATTRIBUTE)),
                () -> assertTrue(elementHasAttributes(
                        registrationTemplate,
                        "p:password",
                        "id=\"passwordConfirmation\"",
                        TRANSPORT_MAXIMUM_LENGTH_ATTRIBUTE)),
                () -> assertTrue(elementHasAttributes(
                        accountSettingsTemplate,
                        "p:password",
                        "id=\"currentPassword\"",
                        TRANSPORT_MAXIMUM_LENGTH_ATTRIBUTE)),
                () -> assertTrue(elementHasAttributes(
                        accountSettingsTemplate,
                        "p:password",
                        "id=\"newPassword\"",
                        TRANSPORT_MAXIMUM_LENGTH_ATTRIBUTE)),
                () -> assertTrue(elementHasAttributes(
                        accountSettingsTemplate,
                        "p:password",
                        "id=\"newPasswordConfirmation\"",
                        TRANSPORT_MAXIMUM_LENGTH_ATTRIBUTE)));
    }

    private static boolean elementHasAttributes(
            String template,
            String elementName,
            String... expectedAttributes) {
        Matcher elementMatcher = Pattern.compile(
                        "<" + Pattern.quote(elementName) + "\\b[^>]*>")
                .matcher(template);
        while (elementMatcher.find()) {
            String element = elementMatcher.group();
            if (Arrays.stream(expectedAttributes).allMatch(element::contains)) {
                return true;
            }
        }
        return false;
    }
}
