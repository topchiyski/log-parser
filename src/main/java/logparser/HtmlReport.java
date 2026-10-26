package logparser;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Renders parsed logs as one self-contained HTML page: summary tiles, level
 * breakdown, a timeline, top errors, spikes, skipped lines and all entries.
 *
 * <p>The page has no external dependencies (charts are inline SVG), so it
 * works offline and can be shared as a single file. Every piece of log text
 * is HTML-escaped, because log messages are untrusted input.
 */
final class HtmlReport {

    private static final DateTimeFormatter TIME_OF_DAY = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY_AND_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    /**
     * How many errors the "most frequent errors" card lists.
     */
    private static final int TOP_ERRORS = 10;

    /**
     * Caps the "all entries" table so huge logs still produce a usable page.
     */
    private static final int MAX_ENTRY_ROWS = 1000;

    /**
     * Candidate timeline bucket sizes; the smallest that fits MAX_BUCKETS wins.
     */
    private static final int[] BUCKET_MINUTES = {1, 5, 15, 30, 60, 180, 360, 720, 1440};
    private static final int MAX_BUCKETS = 60;

    // Timeline geometry, in SVG user units.
    private static final double CHART_WIDTH = 800;
    private static final double CHART_HEIGHT = 240;
    private static final double PLOT_LEFT = 40;
    private static final double PLOT_RIGHT = 10;
    private static final double PLOT_TOP = 10;
    private static final double PLOT_BOTTOM = 30;

    private HtmlReport() {
        // Not instantiable: static helpers only.
    }

    // ---------------------------------------------------------------------
    // Page
    // ---------------------------------------------------------------------

    /**
     * Renders the report page.
     *
     * @param parser      the parser used to analyze each log
     * @param logs        source name to raw lines, in display order
     * @param generatedAt the time shown in the page header
     * @return the complete HTML document
     */
    static String render(LogParser parser, Map<String, List<String>> logs, LocalDateTime generatedAt) {
        StringBuilder html = new StringBuilder();
        html.append("""
                <!doctype html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Log Report</title>
                <style>""").append(CSS).append("""
                </style>
                </head>
                <body>
                <main>
                <header>
                <h1>Log report</h1>
                """);
        html.append("<p class=\"muted\">Generated ").append(generatedAt.format(LogParser.TIMESTAMP_FORMAT)).append(" &middot; ").append(logs.size()).append(logs.size() == 1 ? " log" : " logs").append("</p>\n");

        // With several logs, link to each section from the header.
        if (logs.size() > 1) {
            html.append("<nav>");
            int index = 0;
            for (String source : logs.keySet()) {
                html.append("<a href=\"#log-").append(++index).append("\">").append(escape(source)).append("</a>");
            }
            html.append("</nav>\n");
        }
        html.append("</header>\n");

        if (logs.isEmpty()) {
            html.append("<p class=\"empty\">No logs to show.</p>\n");
        }
        int index = 0;
        for (Map.Entry<String, List<String>> log : logs.entrySet()) {
            renderLog(html, parser, "log-" + ++index, log.getKey(), log.getValue());
        }

        html.append("</main>\n</body>\n</html>\n");
        return html.toString();
    }

    /**
     * Renders one log's section.
     */
    private static void renderLog(StringBuilder html, LogParser parser, String id, String source, List<String> lines) {
        // One pass sorts every line into a parsed entry or a skipped line.
        List<LogEntry> entries = new ArrayList<>();
        Map<Integer, String> skippedLines = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            int lineNumber = i + 1;
            String line = lines.get(i);
            parser.parseLine(line).ifPresentOrElse(entries::add, () -> skippedLines.put(lineNumber, line));
        }

        Map<LogLevel, Integer> counts = parser.countByLevel(entries);
        List<LogParser.ErrorSpike> spikes = parser.detectErrorSpikes(entries);
        int errors = counts.get(LogLevel.ERROR);
        int skipped = skippedLines.size();

        html.append("<section id=\"").append(id).append("\">\n");
        html.append("<h2>").append(escape(source)).append("</h2>\n");

        // Summary tiles.
        html.append("<div class=\"tiles\">\n");
        tile(html, "Lines read", String.valueOf(lines.size()), "", "");
        tile(html, "Parsed", String.valueOf(entries.size()), "", "");
        tile(html, "Skipped", String.valueOf(skipped), "malformed lines", skipped > 0 ? "warn" : "");
        tile(html, "Errors", String.valueOf(errors), "", errors > 0 ? "error" : "");
        tile(html, "Error rate", percent(errors, entries.size()), "of parsed entries", "");
        tile(html, "Error spikes", String.valueOf(spikes.size()), LogParser.DEFAULT_SPIKE_THRESHOLD + "+ errors in " + LogParser.DEFAULT_SPIKE_WINDOW.toMinutes() + " min", spikes.isEmpty() ? "" : "error");
        if (!entries.isEmpty()) {
            LocalDateTime first = earliest(entries);
            LocalDateTime last = latest(entries);
            tile(html, "Time span", formatDuration(Duration.between(first, last)), first.format(LogParser.TIMESTAMP_FORMAT) + " to " + last.format(LogParser.TIMESTAMP_FORMAT), "");
        }
        html.append("</div>\n");

        if (entries.isEmpty()) {
            html.append("<p class=\"empty\">No entries could be parsed from this log.</p>\n");
        } else {
            html.append("<div class=\"card\"><h3>Timeline</h3>\n");
            renderTimeline(html, entries, spikes);
            html.append("</div>\n");

            html.append("<div class=\"grid\">\n");
            renderLevelBreakdown(html, counts, entries.size());
            renderTopErrors(html, parser.topErrors(entries, TOP_ERRORS));
            html.append("</div>\n");

            renderSpikes(html, spikes);
        }
        renderSkippedLines(html, skippedLines);
        if (!entries.isEmpty()) {
            renderEntries(html, entries, spikes);
        }
        html.append("</section>\n");
    }

    // ---------------------------------------------------------------------
    // Cards
    // ---------------------------------------------------------------------

    private static void tile(StringBuilder html, String label, String value, String note, String cssClass) {
        html.append("<div class=\"tile ").append(cssClass).append("\">").append("<div class=\"label\">").append(escape(label)).append("</div>").append("<div class=\"value\">").append(escape(value)).append("</div>");
        if (!note.isEmpty()) {
            html.append("<div class=\"note\">").append(escape(note)).append("</div>");
        }
        html.append("</div>\n");
    }

    private static void renderLevelBreakdown(StringBuilder html, Map<LogLevel, Integer> counts, int total) {
        html.append("<div class=\"card\"><h3>Entries by level</h3>\n");
        counts.forEach((level, count) -> html.append("<div class=\"bar-row\">").append(badge(level)).append("<div class=\"track\"><div class=\"fill bg-").append(level).append("\" style=\"width:").append(number(total == 0 ? 0 : 100.0 * count / total)).append("%\"></div></div>").append("<div class=\"num\">").append(count).append(" <span class=\"muted\">(").append(percent(count, total)).append(")</span></div>").append("</div>\n"));
        html.append("</div>\n");
    }

    private static void renderTopErrors(StringBuilder html, List<LogParser.ErrorCount> topErrors) {
        html.append("<div class=\"card\"><h3>Most frequent errors</h3>\n");
        if (topErrors.isEmpty()) {
            html.append("<p class=\"empty\">No errors.</p>\n</div>\n");
            return;
        }
        long max = topErrors.get(0).count();
        html.append("<div class=\"table-wrap\"><table><thead><tr>").append("<th>#</th><th>Message</th><th class=\"num\">Count</th></tr></thead><tbody>\n");
        for (int i = 0; i < topErrors.size(); i++) {
            LogParser.ErrorCount error = topErrors.get(i);
            html.append("<tr><td>").append(i + 1).append("</td><td>").append(escape(error.message())).append("<div class=\"track thin\"><div class=\"fill bg-ERROR\" style=\"width:").append(number(100.0 * error.count() / max)).append("%\"></div></div></td>").append("<td class=\"num\">").append(error.count()).append("</td></tr>\n");
        }
        html.append("</tbody></table></div>\n</div>\n");
    }

    private static void renderSpikes(StringBuilder html, List<LogParser.ErrorSpike> spikes) {
        html.append("<div class=\"card\"><h3>Error spikes</h3>\n");
        if (spikes.isEmpty()) {
            html.append("<p class=\"empty\">No error spikes detected.</p>\n</div>\n");
            return;
        }
        html.append("<div class=\"table-wrap\"><table><thead><tr>").append("<th>From</th><th>To</th><th class=\"num\">Duration</th>").append("<th class=\"num\">Errors</th><th>Messages</th></tr></thead><tbody>\n");
        for (LogParser.ErrorSpike spike : spikes) {
            // Distinct messages in the burst, most frequent first.
            Map<String, Long> messages = spike.errors().stream().collect(Collectors.groupingBy(LogEntry::message, LinkedHashMap::new, Collectors.counting()));
            html.append("<tr><td>").append(spike.start().format(LogParser.TIMESTAMP_FORMAT)).append("</td>").append("<td>").append(spike.end().format(LogParser.TIMESTAMP_FORMAT)).append("</td>").append("<td class=\"num\">").append(formatDuration(Duration.between(spike.start(), spike.end()))).append("</td>").append("<td class=\"num\">").append(spike.count()).append("</td><td>");
            messages.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).forEach(m -> html.append(escape(m.getKey())).append(" <span class=\"muted\">&times;").append(m.getValue()).append("</span><br>"));
            html.append("</td></tr>\n");
        }
        html.append("</tbody></table></div>\n</div>\n");
    }

    /**
     * Lists skipped lines by line number; renders nothing if every line parsed.
     */
    private static void renderSkippedLines(StringBuilder html, Map<Integer, String> skippedLines) {
        if (skippedLines.isEmpty()) {
            return;
        }
        // Collapsed by default: real logs can have hundreds of stack-trace lines.
        html.append("<div class=\"card\"><details><summary>Skipped lines (").append(skippedLines.size()).append(")</summary>\n").append("<p class=\"muted\">These lines didn't match <code>YYYY-MM-DD HH:MM:SS LEVEL Message</code>,").append(" had an impossible date, or used an unknown level.</p>\n").append("<div class=\"table-wrap\"><table><thead><tr><th class=\"num\">Line</th><th>Content</th>").append("</tr></thead><tbody>\n");
        skippedLines.forEach((lineNumber, line) -> html.append("<tr><td class=\"num\">").append(lineNumber).append("</td><td>").append(line.isBlank() ? "<span class=\"empty\">(blank line)</span>" : "<code>" + escape(line) + "</code>").append("</td></tr>\n"));
        html.append("</tbody></table></div>\n</details></div>\n");
    }

    private static void renderEntries(StringBuilder html, List<LogEntry> entries, List<LogParser.ErrorSpike> spikes) {
        Set<LogEntry> inSpike = new HashSet<>();
        spikes.forEach(spike -> inSpike.addAll(spike.errors()));

        html.append("<div class=\"card\"><details><summary>All entries (").append(entries.size()).append(")</summary>\n");
        if (!inSpike.isEmpty()) {
            html.append("<p class=\"muted\">Highlighted rows are part of an error spike.</p>\n");
        }
        html.append("<div class=\"table-wrap\"><table><thead><tr>").append("<th>Time</th><th>Level</th><th>Message</th></tr></thead><tbody>\n");
        entries.stream().limit(MAX_ENTRY_ROWS).forEach(entry -> html.append(inSpike.contains(entry) ? "<tr class=\"in-spike\">" : "<tr>").append("<td class=\"nowrap\">").append(entry.timestamp().format(LogParser.TIMESTAMP_FORMAT)).append("</td>").append("<td>").append(badge(entry.level())).append("</td>").append("<td>").append(escape(entry.message())).append("</td></tr>\n"));
        html.append("</tbody></table></div>\n");
        if (entries.size() > MAX_ENTRY_ROWS) {
            html.append("<p class=\"muted\">Showing the first ").append(MAX_ENTRY_ROWS).append(" of ").append(entries.size()).append(" entries.</p>\n");
        }
        html.append("</details></div>\n");
    }

    // ---------------------------------------------------------------------
    // Timeline chart
    // ---------------------------------------------------------------------

    /**
     * Draws a stacked bar per time bucket (one colour per level), with each
     * error spike shaded behind the bars.
     */
    private static void renderTimeline(StringBuilder html, List<LogEntry> entries, List<LogParser.ErrorSpike> spikes) {
        LocalDateTime first = earliest(entries);
        LocalDateTime last = latest(entries);
        long bucketMinutes = bucketMinutes(Duration.between(first, last).toMinutes() + 1);
        LocalDateTime start = bucketStart(first, bucketMinutes);
        int bucketCount = (int) (Duration.between(start, last).toMinutes() / bucketMinutes) + 1;

        // counts[bucket][level ordinal]
        int[][] counts = new int[bucketCount][LogLevel.values().length];
        for (LogEntry entry : entries) {
            int bucket = (int) (Duration.between(start, entry.timestamp()).toMinutes() / bucketMinutes);
            counts[bucket][entry.level().ordinal()]++;
        }
        int maxTotal = 1;
        for (int[] bucket : counts) {
            maxTotal = Math.max(maxTotal, Arrays.stream(bucket).sum());
        }

        double plotWidth = CHART_WIDTH - PLOT_LEFT - PLOT_RIGHT;
        double plotHeight = CHART_HEIGHT - PLOT_TOP - PLOT_BOTTOM;
        double plotBottom = PLOT_TOP + plotHeight;
        double slot = plotWidth / bucketCount;

        // The wrapper scrolls sideways on phones rather than shrinking the
        // chart's text until it can't be read.
        html.append("<div class=\"chart-wrap\"><svg viewBox=\"0 0 ").append(number(CHART_WIDTH)).append(' ').append(number(CHART_HEIGHT)).append("\" role=\"img\" aria-label=\"Log entries per ").append(bucketMinutes).append(" minutes, by level\">\n");

        // Horizontal grid lines with count labels at the top and middle.
        for (int value : new int[]{maxTotal, maxTotal / 2}) {
            if (value == 0) {
                continue;
            }
            double y = plotBottom - plotHeight * value / maxTotal;
            html.append("<line class=\"grid-line\" x1=\"").append(number(PLOT_LEFT)).append("\" x2=\"").append(number(CHART_WIDTH - PLOT_RIGHT)).append("\" y1=\"").append(number(y)).append("\" y2=\"").append(number(y)).append("\"/>").append("<text x=\"").append(number(PLOT_LEFT - 6)).append("\" y=\"").append(number(y + 4)).append("\" text-anchor=\"end\">").append(value).append("</text>\n");
        }
        html.append("<line class=\"axis\" x1=\"").append(number(PLOT_LEFT)).append("\" x2=\"").append(number(CHART_WIDTH - PLOT_RIGHT)).append("\" y1=\"").append(number(plotBottom)).append("\" y2=\"").append(number(plotBottom)).append("\"/>\n");

        // Spike bands go first so the bars are drawn on top of them. Each band
        // covers every whole bucket its spike touches, so it lines up with the
        // bars and stays visible above them even when the spike fits in one.
        for (LogParser.ErrorSpike spike : spikes) {
            long firstBucket = Duration.between(start, spike.start()).toMinutes() / bucketMinutes;
            long lastBucket = Duration.between(start, spike.end()).toMinutes() / bucketMinutes;
            double x1 = PLOT_LEFT + slot * firstBucket;
            double x2 = PLOT_LEFT + slot * (lastBucket + 1);
            html.append("<rect class=\"spike-band\" x=\"").append(number(x1)).append("\" y=\"").append(number(PLOT_TOP)).append("\" width=\"").append(number(x2 - x1)).append("\" height=\"").append(number(plotHeight)).append("\"><title>Error spike: ").append(spike.count()).append(" errors, ").append(spike.start().format(LogParser.TIMESTAMP_FORMAT)).append(" to ").append(spike.end().format(LogParser.TIMESTAMP_FORMAT)).append("</title></rect>\n");
        }

        // One stacked bar per bucket, levels stacked bottom-up in enum order.
        boolean multiDay = !first.toLocalDate().equals(last.toLocalDate());
        for (int bucket = 0; bucket < bucketCount; bucket++) {
            LocalDateTime bucketTime = start.plusMinutes(bucket * bucketMinutes);
            double x = PLOT_LEFT + slot * bucket + slot * 0.1;
            double width = slot * 0.8;
            double y = plotBottom;

            StringBuilder tooltip = new StringBuilder(bucketTime.format(LogParser.TIMESTAMP_FORMAT));
            html.append("<g>");
            for (LogLevel level : LogLevel.values()) {
                int count = counts[bucket][level.ordinal()];
                if (count == 0) {
                    continue;
                }
                double height = plotHeight * count / maxTotal;
                y -= height;
                html.append("<rect class=\"f-").append(level).append("\" x=\"").append(number(x)).append("\" y=\"").append(number(y)).append("\" width=\"").append(number(width)).append("\" height=\"").append(number(height)).append("\"/>");
                tooltip.append(", ").append(count).append(' ').append(level);
            }
            html.append("<title>").append(escape(tooltip.toString())).append("</title></g>\n");
        }

        // Time labels under the first, middle and last buckets.
        // A set, because with one or two buckets these indexes coincide.
        for (int bucket : new TreeSet<>(List.of(0, bucketCount / 2, bucketCount - 1))) {
            LocalDateTime bucketTime = start.plusMinutes(bucket * bucketMinutes);
            String anchor = bucket == 0 ? "start" : bucket == bucketCount - 1 ? "end" : "middle";
            double x = bucket == 0 ? PLOT_LEFT : bucket == bucketCount - 1 ? CHART_WIDTH - PLOT_RIGHT : PLOT_LEFT + slot * (bucket + 0.5);
            html.append("<text x=\"").append(number(x)).append("\" y=\"").append(number(CHART_HEIGHT - 8)).append("\" text-anchor=\"").append(anchor).append("\">").append(bucketTime.format(multiDay ? DAY_AND_TIME : TIME_OF_DAY)).append("</text>\n");
        }
        html.append("</svg></div>\n");

        // Legend.
        html.append("<div class=\"legend\">");
        for (LogLevel level : LogLevel.values()) {
            html.append("<span><i class=\"bg-").append(level).append("\"></i>").append(level).append("</span>");
        }
        if (!spikes.isEmpty()) {
            html.append("<span><i class=\"spike-swatch\"></i>Error spike</span>");
        }
        html.append("<span>Each bar: ").append(formatDuration(Duration.ofMinutes(bucketMinutes))).append("</span>");
        html.append("</div>\n");
    }

    /**
     * Picks the smallest bucket size that keeps the chart to MAX_BUCKETS bars.
     */
    private static long bucketMinutes(long spanMinutes) {
        for (int minutes : BUCKET_MINUTES) {
            if ((spanMinutes + minutes - 1) / minutes <= MAX_BUCKETS) {
                return minutes;
            }
        }
        // Logs spanning months: grow buckets in whole days.
        long days = (spanMinutes / 1440 + MAX_BUCKETS - 1) / MAX_BUCKETS + 1;
        return days * 1440;
    }

    /**
     * Rounds down to a bucket boundary counted from midnight, so labels fall on round times.
     */
    private static LocalDateTime bucketStart(LocalDateTime time, long bucketMinutes) {
        LocalDateTime midnight = time.truncatedTo(ChronoUnit.DAYS);
        if (bucketMinutes >= 1440) {
            return midnight;
        }
        long minuteOfDay = Duration.between(midnight, time).toMinutes();
        return midnight.plusMinutes(minuteOfDay / bucketMinutes * bucketMinutes);
    }

    // ---------------------------------------------------------------------
    // Formatting helpers
    // ---------------------------------------------------------------------

    private static LocalDateTime earliest(List<LogEntry> entries) {
        return entries.stream().map(LogEntry::timestamp).min(Comparator.naturalOrder()).orElseThrow();
    }

    private static LocalDateTime latest(List<LogEntry> entries) {
        return entries.stream().map(LogEntry::timestamp).max(Comparator.naturalOrder()).orElseThrow();
    }

    private static String badge(LogLevel level) {
        return "<span class=\"badge bg-" + level + "\">" + level + "</span>";
    }

    /**
     * Formats a number for HTML attributes and text. Locale.ROOT keeps the
     * decimal point a "." whatever the machine's locale, which CSS and SVG
     * require.
     */
    private static String number(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String percent(long part, long total) {
        return number(total == 0 ? 0 : 100.0 * part / total) + "%";
    }

    /**
     * Formats a duration as its two largest units, e.g. "1h 5m" or "3m 45s".
     */
    static String formatDuration(Duration duration) {
        long days = duration.toDays();
        int hours = duration.toHoursPart();
        int minutes = duration.toMinutesPart();
        int seconds = duration.toSecondsPart();
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0) {
            return seconds == 0 ? minutes + "m" : minutes + "m " + seconds + "s";
        }
        return seconds + "s";
    }

    /**
     * Escapes text for use in HTML element content and quoted attributes.
     */
    static String escape(String text) {
        StringBuilder escaped = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&#39;");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }

    // ---------------------------------------------------------------------
    // Styles
    // ---------------------------------------------------------------------

    /**
     * Page styles. Colours are CSS variables, redefined for dark mode.
     */
    private static final String CSS = """
            
            :root {
              --bg: #f5f6f8; --card: #ffffff; --text: #1b1f24; --muted: #5f6b7a; --border: #dde2e8;
              --debug: #8a94a3; --info: #2f6fdb; --warning: #c98000; --error: #d93a3a;
              --spike: rgba(217, 58, 58, 0.12);
              color-scheme: light;
            }
            @media (prefers-color-scheme: dark) {
              :root {
                --bg: #111418; --card: #1a1f26; --text: #e6e9ee; --muted: #9aa5b4; --border: #2c333d;
                --debug: #8a94a3; --info: #5b8ff0; --warning: #f0a830; --error: #f06464;
                --spike: rgba(240, 100, 100, 0.18);
                color-scheme: dark;
              }
            }
            * { box-sizing: border-box; }
            body { margin: 0; background: var(--bg); color: var(--text);
                   font: 15px/1.5 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
            main { max-width: 1100px; margin: 0 auto; padding: 24px 16px 48px; }
            h1 { margin: 0 0 4px; font-size: 1.6rem; }
            h2 { margin: 36px 0 12px; font-size: 1.3rem; overflow-wrap: anywhere; }
            h3 { margin: 0 0 12px; font-size: 1rem; }
            .muted { color: var(--muted); }
            .empty { color: var(--muted); font-style: italic; }
            nav { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
            nav a { color: var(--info); text-decoration: none; border: 1px solid var(--border);
                    border-radius: 999px; padding: 2px 12px; background: var(--card); }
            .tiles { display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 12px; }
            .tile, .card { background: var(--card); border: 1px solid var(--border); border-radius: 10px;
                           padding: 14px 16px; min-width: 0; }
            .card { margin-top: 12px; }
            .tile .label { color: var(--muted); font-size: 0.75rem; text-transform: uppercase; letter-spacing: 0.04em; }
            .tile .value { font-size: 1.6rem; font-weight: 650; font-variant-numeric: tabular-nums; }
            .tile .note { color: var(--muted); font-size: 0.78rem; }
            .tile.error .value { color: var(--error); }
            .tile.warn .value { color: var(--warning); }
            .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 340px), 1fr));
                    gap: 12px; margin-top: 12px; }
            .grid > .card { margin-top: 0; }
            .bar-row { display: grid; grid-template-columns: 80px 1fr 96px; align-items: center; gap: 8px; margin: 8px 0; }
            .track { background: var(--border); border-radius: 4px; height: 10px; overflow: hidden; }
            .track.thin { height: 4px; margin-top: 4px; }
            .fill { height: 100%; border-radius: 4px; }
            .num { text-align: right; font-variant-numeric: tabular-nums; }
            .nowrap { white-space: nowrap; }
            .badge { display: inline-block; font-size: 0.7rem; font-weight: 650; padding: 1px 7px;
                     border-radius: 4px; color: #fff; }
            .bg-DEBUG { background: var(--debug); }
            .bg-INFO { background: var(--info); }
            .bg-WARNING { background: var(--warning); }
            .bg-ERROR { background: var(--error); }
            .f-DEBUG { fill: var(--debug); }
            .f-INFO { fill: var(--info); }
            .f-WARNING { fill: var(--warning); }
            .f-ERROR { fill: var(--error); }
            .chart-wrap { overflow-x: auto; }
            svg { display: block; width: 100%; min-width: 560px; height: auto; }
            svg text { fill: var(--muted); font-size: 11px; }
            svg .grid-line { stroke: var(--border); stroke-dasharray: 3 3; }
            svg .axis { stroke: var(--border); }
            .spike-band { fill: var(--spike); }
            .legend { display: flex; flex-wrap: wrap; gap: 6px 16px; font-size: 0.85rem; color: var(--muted); margin-top: 8px; }
            .legend i { display: inline-block; width: 10px; height: 10px; border-radius: 2px; margin-right: 5px; }
            .legend .spike-swatch { background: var(--spike); outline: 1px solid var(--error); }
            .table-wrap { overflow-x: auto; }
            table { width: 100%; border-collapse: collapse; font-size: 0.9rem; }
            th, td { text-align: left; padding: 6px 8px; border-bottom: 1px solid var(--border); vertical-align: top; }
            th { color: var(--muted); font-weight: 600; font-size: 0.75rem; text-transform: uppercase; }
            th.num, td.num { text-align: right; }
            tr.in-spike td { background: var(--spike); }
            code { font-family: ui-monospace, Consolas, monospace; font-size: 0.85rem;
                   white-space: pre-wrap; overflow-wrap: anywhere; }
            details summary { cursor: pointer; font-weight: 600; }
            """;
}
