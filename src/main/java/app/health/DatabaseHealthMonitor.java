package app.health;

import jakarta.annotation.Resource;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

@ApplicationScoped
public class DatabaseHealthMonitor {
    static final int DATABASE_VALIDATION_TIMEOUT_SECONDS = 2;

    @Resource(lookup = "jdbc/CalendarDataSource")
    private DataSource dataSource;

    public boolean isDatabaseUsable() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(DATABASE_VALIDATION_TIMEOUT_SECONDS);
        } catch (SQLException | RuntimeException exception) {
            return false;
        }
    }
}
