package app.health;

import jakarta.inject.Inject;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@WebServlet("/health")
public class HealthServlet extends HttpServlet {
    static final String DEPLOYMENT_REVISION_HEADER = "X-Deployment-Revision";

    private String deploymentRevision;

    @Inject
    private DatabaseHealthMonitor databaseHealthMonitor;

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
        if (databaseHealthMonitor.isDatabaseUsable()) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write("ok");
            return;
        }

        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.getWriter().write("unavailable");
    }
}
