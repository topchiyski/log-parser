package logparser;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Demonstrates LogParser on log files.
 *
 * <p>With no arguments it analyzes the sample logs bundled under
 * {@code src/main/resources/logs/}. Any arguments are treated as paths to log
 * files on disk, which are analyzed instead (see {@link LogSources}).
 */
public final class LogParserDemo {

    private static final Logger LOG = LoggerFactory.getLogger(LogParserDemo.class);

    private LogParserDemo() {
        // Not instantiable: this class only holds main().
    }

    public static void main(String[] args) {
        LogParser parser = new LogParser();
        LogSources.load(List.of(args)).forEach((source, lines) -> analyze(parser, source, lines));
    }

    /** Runs every analysis on one log and logs the results. */
    private static void analyze(LogParser parser, String source, List<String> lines) {
        LOG.info("Analyzing {}", source);

        // 1. Parse every line, skipping the malformed ones.
        List<LogEntry> entries = parser.parseLines(lines);
        LOG.info("Parsed {} of {} lines ({} skipped)",
                entries.size(), lines.size(), lines.size() - entries.size());

        // 2. Count entries per level.
        Map<LogLevel, Integer> counts = parser.countByLevel(entries);
        LOG.info("Count by level: {}", counts);

        // 3. List every repeated error.
        for (LogParser.ErrorCount error : parser.findRepeatedErrors(entries)) {
            LOG.info("Repeated error ({}x): {}", error.count(), error.message());
        }

        // 4. The alert report lists the top errors and every spike. It is
        // multi-line text, so it starts on the line after the log prefix.
        LOG.warn("Alert report for {}:{}{}",
                source, System.lineSeparator(), parser.buildAlertReport(entries).stripTrailing());
    }
}
