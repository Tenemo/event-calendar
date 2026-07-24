package app.web;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
