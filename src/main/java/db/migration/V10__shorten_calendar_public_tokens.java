package db.migration;

import app.calendar.CalendarPublicToken;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V10__shorten_calendar_public_tokens extends BaseJavaMigration {
    private static final int MAXIMUM_GENERATION_ATTEMPTS_PER_TOKEN = 100;

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        try (Statement constraintStatement = connection.createStatement()) {
            constraintStatement.execute("set local lock_timeout = '10s'");
            constraintStatement.execute("lock table calendar in access exclusive mode");
            constraintStatement.execute("alter table calendar drop constraint calendar_public_token_check");
        }
        List<Long> calendarIds = findCalendarIds(connection);
        List<String> publicTokens = generateUniqueTokens(calendarIds.size(), new SecureRandom());

        try (PreparedStatement updateStatement = connection.prepareStatement(
                "update calendar set public_token = ? where id = ?")) {
            for (int calendarIndex = 0; calendarIndex < calendarIds.size(); calendarIndex++) {
                updateStatement.setString(1, publicTokens.get(calendarIndex));
                updateStatement.setLong(2, calendarIds.get(calendarIndex));
                updateStatement.addBatch();
            }
            updateStatement.executeBatch();
        }

        try (Statement constraintStatement = connection.createStatement()) {
            constraintStatement.execute(
                    "alter table calendar add constraint calendar_public_token_check "
                            + "check (public_token ~ '^[A-Za-z0-9_-]{10}[AEIMQUYcgkosw048]$')");
        }
    }

    static List<String> generateUniqueTokens(int tokenCount, SecureRandom secureRandom) {
        if (tokenCount < 0) {
            throw new IllegalArgumentException("Token count cannot be negative.");
        }
        List<String> tokens = new ArrayList<>(tokenCount);
        Set<String> uniqueTokens = new HashSet<>(tokenCount);
        for (int tokenIndex = 0; tokenIndex < tokenCount; tokenIndex++) {
            String token = null;
            for (int attempt = 0; attempt < MAXIMUM_GENERATION_ATTEMPTS_PER_TOKEN; attempt++) {
                String candidate = CalendarPublicToken.generate(secureRandom);
                if (uniqueTokens.add(candidate)) {
                    token = candidate;
                    break;
                }
            }
            if (token == null) {
                throw new IllegalStateException("Could not generate unique calendar public tokens.");
            }
            tokens.add(token);
        }
        return tokens;
    }

    private static List<Long> findCalendarIds(Connection connection) throws Exception {
        List<Long> calendarIds = new ArrayList<>();
        try (PreparedStatement queryStatement = connection.prepareStatement(
                        "select id from calendar order by id");
                ResultSet resultSet = queryStatement.executeQuery()) {
            while (resultSet.next()) {
                calendarIds.add(resultSet.getLong("id"));
            }
        }
        return calendarIds;
    }
}
