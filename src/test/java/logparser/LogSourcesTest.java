package logparser;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests that the sample logs bundled with the programs can be read and still
 * produce the results documented in README.md and CLAUDE.md.
 */
public class LogSourcesTest {

    private final LogParser parser = new LogParser();

    // The expected numbers below come from the generated sample logs. If the
    // logs are regenerated, update them here and in README.md and CLAUDE.md.

    @Test
    public void applicationLogParsesAsDocumented() {
        List<String> lines = LogSources.readResource("logs/application.log");
        List<LogEntry> entries = parser.parseLines(lines);

        // The line count includes blank lines inside the file; a blank last
        // line would be dropped by the reader.
        assertEquals(lines.size(), 1510);
        assertEquals(entries.size(), 1432);
        assertEquals(parser.countByLevel(entries), Map.of(
                LogLevel.DEBUG, 381,
                LogLevel.INFO, 816,
                LogLevel.WARNING, 199,
                LogLevel.ERROR, 36));
        assertEquals(spikeSizes(entries), List.of(4, 3));
    }

    @Test
    public void incidentLogParsesAsDocumented() {
        List<String> lines = LogSources.readResource("logs/incident.log");
        List<LogEntry> entries = parser.parseLines(lines);

        assertEquals(lines.size(), 1383);
        assertEquals(entries.size(), 1278);
        assertEquals(parser.countByLevel(entries), Map.of(
                LogLevel.DEBUG, 221,
                LogLevel.INFO, 622,
                LogLevel.WARNING, 226,
                LogLevel.ERROR, 209));
        // Six back-to-back spikes during the 10:15-10:45 outage, then the 11:33 aftershock.
        assertEquals(spikeSizes(entries), List.of(29, 32, 38, 35, 33, 33, 3));
        assertEquals(parser.topErrors(entries, 1),
                List.of(new LogParser.ErrorCount("Database connection refused", 130)));
    }

    @Test
    public void readResourceRejectsMissingResource() {
        assertThrows(IllegalStateException.class, () -> LogSources.readResource("logs/missing.log"));
    }

    @Test
    public void loadWithoutPathsReturnsBundledLogsInOrder() {
        assertEquals(List.copyOf(LogSources.load(List.of()).keySet()), LogSources.BUNDLED_LOGS);
    }

    @Test
    public void loadSkipsFilesThatCannotBeRead() {
        assertTrue(LogSources.load(List.of("does-not-exist.log")).isEmpty());
    }

    private List<Integer> spikeSizes(List<LogEntry> entries) {
        return parser.detectErrorSpikes(entries).stream()
                .map(LogParser.ErrorSpike::count)
                .toList();
    }
}
