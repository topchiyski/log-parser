package logparser;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests for {@link LogParser}: parsing, level counts and error pattern
 * detection.
 */
public class LogParserTest {

    private LogParser parser;

    @BeforeMethod
    public void setUp() {
        parser = new LogParser();
    }

    /** Parses the given lines, so tests can describe input as raw log text. */
    private List<LogEntry> parse(String... lines) {
        return parser.parseLines(Arrays.asList(lines));
    }

    // ---------------------------------------------------------------------
    // parseLine
    // ---------------------------------------------------------------------

    @Test
    public void parseLineReadsTimestampLevelAndMessage() {
        Optional<LogEntry> entry = parser.parseLine("2026-10-26 08:10:42 ERROR Failed to process payment: timeout");

        assertTrue(entry.isPresent());
        assertEquals(entry.get().timestamp(), LocalDateTime.of(2026, 10, 26, 8, 10, 42));
        assertEquals(entry.get().level(), LogLevel.ERROR);
        assertEquals(entry.get().message(), "Failed to process payment: timeout");
    }

    @Test
    public void parseLineAcceptsMissingMessageAsEmptyString() {
        Optional<LogEntry> entry = parser.parseLine("2026-01-01 10:00:00 INFO");

        assertTrue(entry.isPresent());
        assertEquals(entry.get().message(), "");
    }

    @Test
    public void parseLineIgnoresSurroundingWhitespace() {
        Optional<LogEntry> entry = parser.parseLine("  2026-01-01 10:00:00   WARNING   Disk usage at 85%  ");

        assertTrue(entry.isPresent());
        assertEquals(entry.get().level(), LogLevel.WARNING);
        assertEquals(entry.get().message(), "Disk usage at 85%");
    }

    @Test
    public void parseLineAcceptsLeapDay() {
        assertTrue(parser.parseLine("2024-02-29 12:00:00 INFO Leap day").isPresent());
    }

    @DataProvider
    public Object[][] malformedLines() {
        return new Object[][] {
                {null},
                {""},
                {"   "},
                {"    at com.example.PaymentService.charge(PaymentService.java:42)"},
                {"2026-10-26 INFO Missing time"},
                {"2026-10-26T08:00:00 INFO ISO separator"},
                {"2026-02-30 09:00:00 INFO Impossible date"},
                {"2025-02-29 09:00:00 INFO Not a leap year"},
                {"2026-10-26 24:00:00 INFO Hour out of range"},
                {"2026-10-26 09:00:00 TRACE Unknown level"},
                {"2026-10-26 09:00:00 WARN Abbreviated level"},
                {"2026-10-26 09:00:00 info Lowercase level"},
        };
    }

    @Test(dataProvider = "malformedLines")
    public void parseLineRejectsMalformedInput(String line) {
        assertFalse(parser.parseLine(line).isPresent(), "Expected to reject: " + line);
    }

    // ---------------------------------------------------------------------
    // parseLines and countByLevel
    // ---------------------------------------------------------------------

    @Test
    public void parseLinesSkipsMalformedLinesAndKeepsOrder() {
        List<LogEntry> entries = parse(
                "2026-10-26 08:00:02 DEBUG Second in time, first in input",
                "not a log line",
                "2026-10-26 08:00:01 INFO First in time, second in input");

        assertEquals(entries.size(), 2);
        assertEquals(entries.get(0).level(), LogLevel.DEBUG);
        assertEquals(entries.get(1).level(), LogLevel.INFO);
    }

    @Test
    public void parseLinesReturnsUnmodifiableList() {
        List<LogEntry> entries = parse("2026-10-26 08:00:01 INFO Started");

        assertThrows(UnsupportedOperationException.class, () -> entries.add(entries.get(0)));
    }

    @Test
    public void countByLevelIncludesEveryLevelInDeclarationOrder() {
        Map<LogLevel, Integer> counts = parser.countByLevel(parse(
                "2026-10-26 08:00:01 INFO a",
                "2026-10-26 08:00:02 INFO b",
                "2026-10-26 08:00:03 ERROR c"));

        assertEquals(List.copyOf(counts.keySet()), List.of(LogLevel.values()));
        assertEquals(counts.get(LogLevel.DEBUG), Integer.valueOf(0));
        assertEquals(counts.get(LogLevel.INFO), Integer.valueOf(2));
        assertEquals(counts.get(LogLevel.WARNING), Integer.valueOf(0));
        assertEquals(counts.get(LogLevel.ERROR), Integer.valueOf(1));
    }

    @Test
    public void countByLevelOfNoEntriesIsAllZeros() {
        Map<LogLevel, Integer> counts = parser.countByLevel(List.of());

        assertEquals(counts.size(), LogLevel.values().length);
        counts.values().forEach(count -> assertEquals(count, Integer.valueOf(0)));
    }

    // ---------------------------------------------------------------------
    // findRepeatedErrors and topErrors
    // ---------------------------------------------------------------------

    @Test
    public void findRepeatedErrorsReturnsOnlyRepeatedErrorMessagesMostFrequentFirst() {
        List<LogParser.ErrorCount> repeated = parser.findRepeatedErrors(parse(
                "2026-10-26 08:00:00 ERROR Disk full",
                "2026-10-26 08:01:00 ERROR Timeout",
                "2026-10-26 08:02:00 ERROR Timeout",
                "2026-10-26 08:03:00 ERROR Disk full",
                "2026-10-26 08:04:00 ERROR Timeout",
                "2026-10-26 08:05:00 ERROR Seen once",
                // Same message at a non-ERROR level must not be counted.
                "2026-10-26 08:06:00 WARNING Seen once"));

        assertEquals(repeated, List.of(
                new LogParser.ErrorCount("Timeout", 3),
                new LogParser.ErrorCount("Disk full", 2)));
    }

    @Test
    public void findRepeatedErrorsComparesMessagesExactly() {
        List<LogParser.ErrorCount> repeated = parser.findRepeatedErrors(parse(
                "2026-10-26 08:00:00 ERROR Timeout",
                "2026-10-26 08:01:00 ERROR timeout"));

        assertTrue(repeated.isEmpty());
    }

    @Test
    public void topErrorsBreaksTiesByFirstAppearanceAndRespectsLimit() {
        List<LogEntry> entries = parse(
                "2026-10-26 08:00:00 ERROR B",
                "2026-10-26 08:01:00 ERROR A",
                "2026-10-26 08:02:00 ERROR C",
                "2026-10-26 08:03:00 ERROR C");

        assertEquals(parser.topErrors(entries, 2), List.of(
                new LogParser.ErrorCount("C", 2),
                new LogParser.ErrorCount("B", 1)));
        assertEquals(parser.topErrors(entries, 10).size(), 3);
        assertTrue(parser.topErrors(entries, 0).isEmpty());
    }

    @Test
    public void topErrorsRejectsNegativeLimit() {
        assertThrows(IllegalArgumentException.class, () -> parser.topErrors(List.of(), -1));
    }

    // ---------------------------------------------------------------------
    // detectErrorSpikes
    // ---------------------------------------------------------------------

    @Test
    public void detectErrorSpikesFindsThreeErrorsWithinFiveMinutes() {
        List<LogParser.ErrorSpike> spikes = parser.detectErrorSpikes(parse(
                "2026-10-26 09:00:00 ERROR a",
                "2026-10-26 09:02:00 ERROR b",
                "2026-10-26 09:04:00 ERROR c"));

        assertEquals(spikes.size(), 1);
        assertEquals(spikes.get(0).start(), LocalDateTime.of(2026, 10, 26, 9, 0, 0));
        assertEquals(spikes.get(0).end(), LocalDateTime.of(2026, 10, 26, 9, 4, 0));
        assertEquals(spikes.get(0).count(), 3);
    }

    @Test
    public void detectErrorSpikesIncludesErrorExactlyAtWindowEnd() {
        List<LogParser.ErrorSpike> spikes = parser.detectErrorSpikes(parse(
                "2026-10-26 09:00:00 ERROR a",
                "2026-10-26 09:01:00 ERROR b",
                "2026-10-26 09:05:00 ERROR c"));

        assertEquals(spikes.size(), 1);
    }

    @Test
    public void detectErrorSpikesExcludesErrorOneSecondPastWindow() {
        List<LogParser.ErrorSpike> spikes = parser.detectErrorSpikes(parse(
                "2026-10-26 09:00:00 ERROR a",
                "2026-10-26 09:01:00 ERROR b",
                "2026-10-26 09:05:01 ERROR c"));

        assertTrue(spikes.isEmpty());
    }

    @Test
    public void detectErrorSpikesIgnoresNonErrorLevels() {
        List<LogParser.ErrorSpike> spikes = parser.detectErrorSpikes(parse(
                "2026-10-26 09:00:00 ERROR a",
                "2026-10-26 09:01:00 WARNING b",
                "2026-10-26 09:02:00 ERROR c"));

        assertTrue(spikes.isEmpty());
    }

    @Test
    public void detectErrorSpikesFindsBurstThatDoesNotStartAtFirstError() {
        // From 09:00 only one other error is within 5 minutes, but from 09:04
        // there are three.
        List<LogParser.ErrorSpike> spikes = parser.detectErrorSpikes(parse(
                "2026-10-26 09:00:00 ERROR a",
                "2026-10-26 09:04:00 ERROR b",
                "2026-10-26 09:05:30 ERROR c",
                "2026-10-26 09:06:00 ERROR d"));

        assertEquals(spikes.size(), 1);
        assertEquals(spikes.get(0).start(), LocalDateTime.of(2026, 10, 26, 9, 4, 0));
        assertEquals(spikes.get(0).count(), 3);
    }

    @Test
    public void detectErrorSpikesReportsEachBurstOnce() {
        List<LogParser.ErrorSpike> spikes = parser.detectErrorSpikes(parse(
                "2026-10-26 09:00:00 ERROR a",
                "2026-10-26 09:01:00 ERROR b",
                "2026-10-26 09:02:00 ERROR c",
                "2026-10-26 09:03:00 ERROR d",
                "2026-10-26 09:04:00 ERROR e",
                // Second, separate burst.
                "2026-10-26 10:00:00 ERROR f",
                "2026-10-26 10:00:01 ERROR g",
                "2026-10-26 10:00:02 ERROR h"));

        assertEquals(spikes.size(), 2);
        assertEquals(spikes.get(0).count(), 5);
        assertEquals(spikes.get(1).count(), 3);
    }

    @Test
    public void detectErrorSpikesSortsUnorderedInput() {
        List<LogParser.ErrorSpike> spikes = parser.detectErrorSpikes(parse(
                "2026-10-26 09:04:00 ERROR c",
                "2026-10-26 09:00:00 ERROR a",
                "2026-10-26 09:02:00 ERROR b"));

        assertEquals(spikes.size(), 1);
        List<String> messages = spikes.get(0).errors().stream().map(LogEntry::message).toList();
        assertEquals(messages, List.of("a", "b", "c"));
    }

    @Test
    public void detectErrorSpikesHonoursCustomThresholdAndWindow() {
        List<LogEntry> entries = parse(
                "2026-10-26 09:00:00 ERROR a",
                "2026-10-26 09:00:30 ERROR b");

        assertEquals(parser.detectErrorSpikes(entries, 2, Duration.ofMinutes(1)).size(), 1);
        assertTrue(parser.detectErrorSpikes(entries, 2, Duration.ofSeconds(10)).isEmpty());
    }

    @Test
    public void detectErrorSpikesRejectsInvalidSettings() {
        assertThrows(IllegalArgumentException.class,
                () -> parser.detectErrorSpikes(List.of(), 0, Duration.ofMinutes(5)));
        assertThrows(IllegalArgumentException.class,
                () -> parser.detectErrorSpikes(List.of(), 3, Duration.ofMinutes(-1)));
        assertThrows(IllegalArgumentException.class,
                () -> parser.detectErrorSpikes(List.of(), 3, null));
    }

    // ---------------------------------------------------------------------
    // buildAlertReport
    // ---------------------------------------------------------------------

    @Test
    public void buildAlertReportListsTopErrorsAndSpikes() {
        String report = parser.buildAlertReport(parse(
                "2026-10-26 09:00:00 ERROR Database connection refused",
                "2026-10-26 09:01:00 ERROR Database connection refused",
                "2026-10-26 09:02:00 ERROR Timeout",
                "2026-10-26 12:00:00 ERROR Disk full",
                "2026-10-26 13:00:00 ERROR Cache miss"));

        List<String> lines = report.lines().toList();
        assertEquals(lines, List.of(
                "Top 3 most frequent errors:",
                "  1. Database connection refused (2x)",
                "  2. Timeout (1x)",
                "  3. Disk full (1x)",
                "Error spikes (3+ errors within 5 minutes):",
                "  2026-10-26 09:00:00 to 2026-10-26 09:02:00: 3 errors"));
    }

    @Test
    public void buildAlertReportSaysSoWhenThereAreNoErrors() {
        String report = parser.buildAlertReport(parse("2026-10-26 09:00:00 INFO All good"));

        assertTrue(report.contains("(no errors)"), report);
        assertTrue(report.contains("(none detected)"), report);
    }
}
