package logparser;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * One parsed log line.
 *
 * <p>A record keeps the class immutable: the fields are private and final,
 * and the compiler generates the accessors, equals, hashCode and toString.
 *
 * @param timestamp when the event was logged
 * @param level     the severity of the event
 * @param message   the free-text part of the line (may be empty, never null)
 */
public record LogEntry(LocalDateTime timestamp, LogLevel level, String message) {

    /** Rejects nulls so every LogEntry in the program is complete. */
    public LogEntry {
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        Objects.requireNonNull(level, "level must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }
}
