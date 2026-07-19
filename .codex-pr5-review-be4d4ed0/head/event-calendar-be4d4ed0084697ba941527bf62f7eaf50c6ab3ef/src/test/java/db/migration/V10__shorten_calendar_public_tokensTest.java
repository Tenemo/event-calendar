package db.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

final class V10__shorten_calendar_public_tokensTest {
    @Test
    void retriesARepeatedTokenAndPreservesInputOrder() {
        SequenceSecureRandom secureRandom = new SequenceSecureRandom(
                bytes(1), bytes(1), bytes(2), bytes(3));

        List<String> tokens = V10__shorten_calendar_public_tokens.generateUniqueTokens(3, secureRandom);

        assertEquals(3, tokens.size());
        assertEquals(3, tokens.stream().distinct().count());
        assertTrue(tokens.stream().allMatch(V10__shorten_calendar_public_tokens::isValidPublicToken));
        assertEquals(4, secureRandom.getRequestCount());
    }

    @Test
    void rejectsInvalidCountsAndFailsClosedWhenRandomnessKeepsColliding() {
        assertThrows(
                IllegalArgumentException.class,
                () -> V10__shorten_calendar_public_tokens.generateUniqueTokens(-1, new SecureRandom()));
        assertThrows(
                IllegalStateException.class,
                () -> V10__shorten_calendar_public_tokens.generateUniqueTokens(
                        2, new RepeatingSecureRandom(bytes(9))));
    }

    private static byte[] bytes(int finalByte) {
        byte[] bytes = new byte[8];
        bytes[bytes.length - 1] = (byte) finalByte;
        return bytes;
    }

    private static final class SequenceSecureRandom extends SecureRandom {
        private final Deque<byte[]> values;
        private int requestCount;

        private SequenceSecureRandom(byte[]... values) {
            this.values = new ArrayDeque<>(List.of(values));
        }

        @Override
        public void nextBytes(byte[] bytes) {
            requestCount++;
            byte[] value = values.removeFirst();
            System.arraycopy(value, 0, bytes, 0, bytes.length);
        }

        private int getRequestCount() {
            return requestCount;
        }
    }

    private static final class RepeatingSecureRandom extends SecureRandom {
        private final byte[] value;

        private RepeatingSecureRandom(byte[] value) {
            this.value = Arrays.copyOf(value, value.length);
        }

        @Override
        public void nextBytes(byte[] bytes) {
            System.arraycopy(value, 0, bytes, 0, bytes.length);
        }
    }
}
