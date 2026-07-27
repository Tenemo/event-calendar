package app.endtoend;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import java.net.URI;

final class EndToEndBrowser implements AutoCloseable {
    private final Browser browser;
    private final boolean ignoreHttpsErrors;

    private EndToEndBrowser(Browser browser, URI applicationBaseUri) {
        this.browser = browser;
        ignoreHttpsErrors = "https".equalsIgnoreCase(applicationBaseUri.getScheme());
    }

    static EndToEndBrowser launch(
            BrowserType browserType,
            boolean headless,
            URI applicationBaseUri) {
        Browser browser = browserType.launch(
                new BrowserType.LaunchOptions().setHeadless(headless));
        return new EndToEndBrowser(browser, applicationBaseUri);
    }

    BrowserContext newContext() {
        return newContext(new Browser.NewContextOptions());
    }

    BrowserContext newContext(Browser.NewContextOptions options) {
        return browser.newContext(options.setIgnoreHTTPSErrors(ignoreHttpsErrors));
    }

    @Override
    public void close() {
        browser.close();
    }
}
