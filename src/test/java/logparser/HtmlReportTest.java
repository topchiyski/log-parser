package logparser;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests for {@link HtmlReport}. They check the content of the page, not its
 * exact markup, so styling changes don't break them.
 */
public class HtmlReportTest {

    private static final LocalDateTime GENERATED_AT = LocalDateTime.of(2026, 10, 26, 12, 0, 0);

    private final LogParser parser = new LogParser();

    /** Renders a report for one log made of the given lines. */
    private String render(String... lines) {
        return HtmlReport.render(parser, Map.of("test.log", List.of(lines)), GENERATED_AT);
    }

    @Test
    public void reportIsACompleteHtmlPage() {
        String html = render("2026-10-26 09:00:00 INFO Started");

        assertTrue(html.startsWith("<!doctype html>"));
        assertTrue(html.contains("<title>Log Report</title>"));
        assertTrue(html.contains("Generated 2026-10-26 12:00:00"));
        assertTrue(html.trim().endsWith("</html>"));
    }

    @Test
    public void reportShowsSpikesAndTopErrorsForIncidentLog() {
        Map<String, List<String>> logs = Map.of(
                "logs/incident.log", LogSources.readResource("logs/incident.log"));
        String html = HtmlReport.render(parser, logs, GENERATED_AT);

        assertTrue(html.contains("2026-10-26 10:15:14"), "first spike start");
        assertTrue(html.contains("2026-10-26 11:34:53"), "last spike end");
        assertTrue(html.contains("Database connection refused"));
        assertEquals(count(html, "class=\"spike-band\""), 7);
    }

    @Test
    public void reportListsSkippedLinesWithLineNumbers() {
        String html = render(
                "2026-10-26 09:00:00 INFO Started",
                "2026-10-26 09:00:01 TRACE Unknown level",
                "");

        assertTrue(html.contains("Skipped lines (2)"));
        assertTrue(html.contains("2026-10-26 09:00:01 TRACE Unknown level"));
        assertTrue(html.contains("(blank line)"));
    }

    @Test
    public void reportOmitsSkippedLinesCardWhenEveryLineParses() {
        assertFalse(render("2026-10-26 09:00:00 INFO Started").contains("Skipped lines"));
    }

    @Test
    public void reportEscapesLogText() {
        String html = render("2026-10-26 09:00:00 ERROR <script>alert('x')</script> & more");

        assertFalse(html.contains("<script>"), "log text must not become markup");
        assertTrue(html.contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt; &amp; more"));
    }

    @Test
    public void reportHandlesLogWithNoParsableEntries() {
        String html = render("not a log line");

        assertTrue(html.contains("No entries could be parsed"));
        assertFalse(html.contains("<svg"));
    }

    @Test
    public void reportHandlesSingleEntry() {
        String html = render("2026-10-26 09:00:00 ERROR Only one");

        assertTrue(html.contains("<svg"));
        assertTrue(html.contains(">09:00</text>"), "single bucket still gets a time label");
    }

    @Test
    public void reportLinksEachLogWhenThereAreSeveral() {
        Map<String, List<String>> logs = new LinkedHashMap<>();
        logs.put("a.log", List.of("2026-10-26 09:00:00 INFO a"));
        logs.put("b.log", List.of("2026-10-26 09:00:00 INFO b"));
        String html = HtmlReport.render(parser, logs, GENERATED_AT);

        assertTrue(html.contains("href=\"#log-1\">a.log</a>"));
        assertTrue(html.contains("href=\"#log-2\">b.log</a>"));
        assertTrue(html.contains("id=\"log-2\""));
    }

    @Test
    public void numbersUseDecimalPointWhateverTheLocale() {
        Locale original = Locale.getDefault();
        try {
            // German formats 50.0 as "50,0", which CSS and SVG reject.
            Locale.setDefault(Locale.GERMANY);
            String html = render(
                    "2026-10-26 09:00:00 INFO a",
                    "2026-10-26 09:00:01 ERROR b");

            assertTrue(html.contains("50.0%"));
            assertFalse(html.contains("50,0"));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    public void formatDurationUsesTwoLargestUnits() {
        assertEquals(HtmlReport.formatDuration(Duration.ofSeconds(42)), "42s");
        assertEquals(HtmlReport.formatDuration(Duration.ofSeconds(225)), "3m 45s");
        assertEquals(HtmlReport.formatDuration(Duration.ofMinutes(5)), "5m");
        assertEquals(HtmlReport.formatDuration(Duration.ofMinutes(65)), "1h 5m");
        assertEquals(HtmlReport.formatDuration(Duration.ofHours(26)), "1d 2h");
    }

    private static int count(String text, String part) {
        int count = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) {
            count++;
        }
        return count;
    }
}
