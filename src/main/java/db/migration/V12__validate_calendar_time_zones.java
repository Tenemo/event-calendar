package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V12__validate_calendar_time_zones extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        CalendarTimeZoneAudit.validateStoredTimeZones(context.getConnection());
    }
}
