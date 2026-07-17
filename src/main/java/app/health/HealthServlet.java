package app.health;

import jakarta.annotation.Resource;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

@WebServlet("/health")
public class HealthServlet extends HttpServlet {
    static final int DATABASE_VALIDATION_TIMEOUT_SECONDS = 2;
    static final String DEPLOYMENT_REVISION_HEADER = "X-Deployment-Revision";

    private String deploymentRevision;

    @Resource(lookup = "jdbc/CalendarDataSource")
    private DataSource dataSource;

    @Override
    public void init() {
        deploymentRevision = DeploymentRevision.fromApplicationArtifact().orElse(null);
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("text/plain; charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        if (deploymentRevision != null) {
            response.setHeader(DEPLOYMENT_REVISION_HEADER, deploymentRevision);
        }
        if (databaseIsUsable()) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write("ok");
            return;
        }

        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.getWriter().write("unavailable");
    }

    private boolean databaseIsUsable() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(DATABASE_VALIDATION_TIMEOUT_SECONDS);
        } catch (SQLException exception) {
            return false;
        }
    }
}
