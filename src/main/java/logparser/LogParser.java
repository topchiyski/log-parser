package logparser;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses log lines of the form {@code YYYY-MM-DD HH:MM:SS LEVEL Message}.
 *
 * <p>Lines that don't match the format are skipped rather than failing the
 * whole parse, because real log files often contain stack traces, blank
 * lines or truncated writes.
 */
public final class LogParser {

    private static final Logger LOG = LoggerFactory.getLogger(LogParser.class);

    // ---------------------------------------------------------------------
    // Format definitions
    // ---------------------------------------------------------------------

    /**
     * Splits a line into three groups: timestamp, level and message.
     * The message is optional so a line like "2026-01-01 10:00:00 INFO" is
     * still accepted, with an empty message.
     */
    private static final Pattern LINE_PATTERN = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})\\s+(\\S+)(?:\\s+(.*))?$");

    /**
     * The log's timestamp format, also used to print timestamps in the same
     * shape (LocalDateTime.toString() uses a "T" and drops ":00" seconds).
     *
     * <p>STRICT resolving rejects impossible dates such as 2026-02-30 instead
     * of silently rolling them over to March. STRICT needs "uuuu" (proleptic
     * year) rather than "yyyy" (year-of-era).
     */
    static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
                    .withResolverStyle(ResolverStyle.STRICT);

    // ---------------------------------------------------------------------
    // Error pattern settings
    // ---------------------------------------------------------------------

    /**
     * Minimum number of errors that counts as a spike.
     */
    public static final int DEFAULT_SPIKE_THRESHOLD = 3;

    /**
     * Time span the spike threshold must be reached within.
     */
    public static final Duration DEFAULT_SPIKE_WINDOW = Duration.ofMinutes(5);

    /**
     * How many of the most frequent errors the alert report lists.
     */
    private static final int REPORT_TOP_ERRORS = 3;

    // ---------------------------------------------------------------------
    // Result types for error pattern detection
    // ---------------------------------------------------------------------

    /**
     * An error message and how many times it occurred.
     *
     * @param message the exact error message
     * @param count   number of ERROR entries with that message
     */
    public record ErrorCount(String message, long count) {
    }

    /**
     * A burst of errors that fell within one spike window.
     *
     * @param errors the errors in the burst, oldest first; never empty
     */
    public record ErrorSpike(List<LogEntry> errors) {

        /**
         * Takes an immutable copy of the error list and rejects an empty one.
         */
        public ErrorSpike {
            errors = List.copyOf(errors);
            if (errors.isEmpty()) {
                throw new IllegalArgumentException("a spike needs at least one error");
            }
        }

        /**
         * Returns the timestamp of the first error in the burst.
         */
        public LocalDateTime start() {
            return errors.get(0).timestamp();
        }

        /**
         * Returns the timestamp of the last error in the burst.
         */
        public LocalDateTime end() {
            return errors.get(errors.size() - 1).timestamp();
        }

        /**
         * Returns how many errors the burst contains.
         */
        public int count() {
            return errors.size();
        }
    }

    // ---------------------------------------------------------------------
    // Parsing
    // ---------------------------------------------------------------------

    /**
     * Parses a single log line.
     *
     * @param line the raw line; null is treated as malformed
     * @return the parsed entry, or empty if the line is malformed, has an
     * invalid date or time, or uses an unknown level
     */
    public Optional<LogEntry> parseLine(String line) {
        if (line == null) {
            LOG.debug("Skipping null line");
            return Optional.empty();
        }

        Matcher matcher = LINE_PATTERN.matcher(line.strip());
        if (!matcher.matches()) {
            LOG.debug("Skipping line that doesn't match the log format: '{}'", line);
            return Optional.empty();
        }

        Optional<LocalDateTime> timestamp = parseTimestamp(matcher.group(1));
        if (timestamp.isEmpty()) {
            LOG.debug("Skipping line with invalid timestamp '{}': '{}'", matcher.group(1), line);
            return Optional.empty();
        }

        Optional<LogLevel> level = LogLevel.fromText(matcher.group(2));
        if (level.isEmpty()) {
            LOG.debug("Skipping line with unknown level '{}': '{}'", matcher.group(2), line);
            return Optional.empty();
        }

        String message = matcher.group(3) == null ? "" : matcher.group(3);
        return Optional.of(new LogEntry(timestamp.get(), level.get(), message));
    }

    /**
     * Parses many lines, keeping only the valid ones, in their original order.
     *
     * @param lines the raw lines
     * @return an unmodifiable list of the entries that parsed successfully
     */
    public List<LogEntry> parseLines(List<String> lines) {
        List<LogEntry> entries = new ArrayList<>();
        for (String line : lines) {
            parseLine(line).ifPresent(entries::add);
        }
        LOG.debug("Parsed {} of {} lines ({} skipped)",
                entries.size(), lines.size(), lines.size() - entries.size());
        return Collections.unmodifiableList(entries);
    }

    /**
     * Returns the parsed timestamp, or empty if it isn't a real date and time.
     */
    private static Optional<LocalDateTime> parseTimestamp(String text) {
        try {
            return Optional.of(LocalDateTime.parse(text, TIMESTAMP_FORMAT));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    // ---------------------------------------------------------------------
    // Statistics
    // ---------------------------------------------------------------------

    /**
     * Counts how many entries there are for each level.
     *
     * <p>Every level appears in the result, with 0 if it never occurs, so
     * callers don't have to handle missing keys. EnumMap iterates in the
     * enum's declaration order (DEBUG, INFO, WARNING, ERROR).
     *
     * @param entries the parsed entries
     * @return an unmodifiable map from level to count
     */
    public Map<LogLevel, Integer> countByLevel(List<LogEntry> entries) {
        Map<LogLevel, Integer> counts = new EnumMap<>(LogLevel.class);
        for (LogLevel level : LogLevel.values()) {
            counts.put(level, 0);
        }
        for (LogEntry entry : entries) {
            counts.merge(entry.level(), 1, Integer::sum);
        }
        return Collections.unmodifiableMap(counts);
    }

    // ---------------------------------------------------------------------
    // Error pattern detection
    // ---------------------------------------------------------------------

    /**
     * Finds error messages that occur more than once.
     *
     * <p>Messages are compared exactly, so "timeout" and "Timeout" are
     * different errors.
     *
     * @param entries the parsed entries
     * @return an unmodifiable list of repeated errors, most frequent first;
     * ties keep the order in which the messages first appeared
     */
    public List<ErrorCount> findRepeatedErrors(List<LogEntry> entries) {
        return countErrorMessages(entries).stream()
                .filter(error -> error.count() > 1)
                .toList();
    }

    /**
     * Returns the most frequent error messages, including ones seen only once.
     *
     * @param entries the parsed entries
     * @param limit   the maximum number of messages to return
     * @return an unmodifiable list of at most {@code limit} errors, most
     * frequent first; ties keep the order of first appearance
     */
    public List<ErrorCount> topErrors(List<LogEntry> entries, int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must not be negative: " + limit);
        }
        return countErrorMessages(entries).stream()
                .limit(limit)
                .toList();
    }

    /**
     * Detects error spikes using the default settings: 3 or more errors
     * within 5 minutes.
     *
     * @param entries the parsed entries
     * @return an unmodifiable list of spikes, oldest first
     * @see #detectErrorSpikes(List, int, Duration)
     */
    public List<ErrorSpike> detectErrorSpikes(List<LogEntry> entries) {
        return detectErrorSpikes(entries, DEFAULT_SPIKE_THRESHOLD, DEFAULT_SPIKE_WINDOW);
    }

    /**
     * Detects bursts of at least {@code threshold} errors where the last error
     * is no more than {@code window} after the first.
     *
     * <p>Errors are sorted by timestamp first, so the input order doesn't
     * matter. A window opens at each error in turn and takes in every later
     * error that falls inside it. When a window holds enough errors it is
     * reported and the search resumes after its last error, so one burst is
     * reported once rather than once per error in it.
     *
     * @param entries   the parsed entries
     * @param threshold the minimum number of errors in a spike (at least 1)
     * @param window    the maximum time between the first and last error
     * @return an unmodifiable list of spikes, oldest first
     */
    public List<ErrorSpike> detectErrorSpikes(List<LogEntry> entries, int threshold, Duration window) {
        if (threshold < 1) {
            throw new IllegalArgumentException("threshold must be at least 1: " + threshold);
        }
        if (window == null || window.isNegative()) {
            throw new IllegalArgumentException("window must be zero or positive: " + window);
        }

        List<LogEntry> errors = entries.stream()
                .filter(entry -> entry.level() == LogLevel.ERROR)
                .sorted(Comparator.comparing(LogEntry::timestamp))
                .toList();

        List<ErrorSpike> spikes = new ArrayList<>();
        int first = 0;
        while (first < errors.size()) {
            LocalDateTime windowEnd = errors.get(first).timestamp().plus(window);

            // Move past every error that falls inside this window.
            int afterLast = first;
            while (afterLast < errors.size()
                    && !errors.get(afterLast).timestamp().isAfter(windowEnd)) {
                afterLast++;
            }

            if (afterLast - first >= threshold) {
                ErrorSpike spike = new ErrorSpike(errors.subList(first, afterLast));
                LOG.debug("Detected error spike: {} errors from {} to {}",
                        spike.count(),
                        spike.start().format(TIMESTAMP_FORMAT),
                        spike.end().format(TIMESTAMP_FORMAT));
                spikes.add(spike);
                first = afterLast;
            } else {
                first++;
            }
        }
        return List.copyOf(spikes);
    }

    /**
     * Builds a plain-text alert report listing the top 3 most frequent errors
     * and every error spike (3 or more errors within 5 minutes).
     *
     * @param entries the parsed entries
     * @return the report, one item per line
     */
    public String buildAlertReport(List<LogEntry> entries) {
        StringBuilder report = new StringBuilder();

        report.append(String.format("Top %d most frequent errors:%n", REPORT_TOP_ERRORS));
        List<ErrorCount> top = topErrors(entries, REPORT_TOP_ERRORS);
        if (top.isEmpty()) {
            report.append(String.format("  (no errors)%n"));
        }
        for (int i = 0; i < top.size(); i++) {
            ErrorCount error = top.get(i);
            report.append(String.format("  %d. %s (%dx)%n", i + 1, error.message(), error.count()));
        }

        report.append(String.format("Error spikes (%d+ errors within %d minutes):%n",
                DEFAULT_SPIKE_THRESHOLD, DEFAULT_SPIKE_WINDOW.toMinutes()));
        List<ErrorSpike> spikes = detectErrorSpikes(entries);
        if (spikes.isEmpty()) {
            report.append(String.format("  (none detected)%n"));
        }
        for (ErrorSpike spike : spikes) {
            report.append(String.format("  %s to %s: %d errors%n",
                    spike.start().format(TIMESTAMP_FORMAT),
                    spike.end().format(TIMESTAMP_FORMAT),
                    spike.count()));
        }
        return report.toString();
    }

    /**
     * Counts ERROR entries per message, most frequent first.
     *
     * <p>The LinkedHashMap keeps messages in order of first appearance, and
     * sorting an ordered stream is stable, so equal counts keep that order.
     */
    private static List<ErrorCount> countErrorMessages(List<LogEntry> entries) {
        Map<String, Long> counts = entries.stream()
                .filter(entry -> entry.level() == LogLevel.ERROR)
                .collect(Collectors.groupingBy(
                        LogEntry::message, LinkedHashMap::new, Collectors.counting()));

        return counts.entrySet().stream()
                .map(e -> new ErrorCount(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(ErrorCount::count).reversed())
                .toList();
    }
}
