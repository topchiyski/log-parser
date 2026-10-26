package logparser;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses logs, writes an HTML report and opens it in the default browser.
 *
 * <p>Arguments are log file paths; with none, the bundled sample logs are
 * used (see {@link LogSources}). {@code --no-open} writes the report without
 * opening a browser.
 */
public final class LogReportApp {

    private static final Logger LOG = LoggerFactory.getLogger(LogReportApp.class);

    /** Where the report is written, relative to the working directory. */
    static final Path OUTPUT = Path.of("build", "reports", "log-report", "index.html");

    private static final String NO_OPEN = "--no-open";

    private LogReportApp() {
        // Not instantiable: this class only holds main().
    }

    public static void main(String[] args) throws IOException {
        List<String> paths = new ArrayList<>(List.of(args));
        boolean open = !paths.remove(NO_OPEN);

        Map<String, List<String>> logs = LogSources.load(paths);
        if (logs.isEmpty()) {
            LOG.error("None of the given logs could be read; no report written");
            System.exit(1);
        }

        String html = HtmlReport.render(new LogParser(), logs, LocalDateTime.now());
        Files.createDirectories(OUTPUT.getParent());
        Files.writeString(OUTPUT, html, StandardCharsets.UTF_8);
        URI uri = OUTPUT.toAbsolutePath().toUri();
        LOG.info("Wrote report for {} log(s) to {}", logs.size(), uri);

        if (open) {
            openInBrowser(uri);
        }
    }

    /** Opens the report in the default browser, or says where to find it. */
    private static void openInBrowser(URI uri) {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            try {
                Desktop.getDesktop().browse(uri);
                LOG.info("Opened the report in your browser");
                return;
            } catch (IOException | UnsupportedOperationException e) {
                LOG.warn("Could not open a browser", e);
            }
        }
        LOG.warn("Open the report manually: {}", uri);
    }
}
