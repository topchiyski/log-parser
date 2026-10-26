package logparser;

import java.util.Optional;

/**
 * The severity levels a log line can have.
 */
public enum LogLevel {
    DEBUG,
    INFO,
    WARNING,
    ERROR;

    /**
     * Looks up a level by its exact name as it appears in a log line.
     *
     * @param text the level token, e.g. "ERROR"
     * @return the matching level, or empty if the text isn't a known level
     */
    public static Optional<LogLevel> fromText(String text) {
        for (LogLevel level : values()) {
            if (level.name().equals(text)) {
                return Optional.of(level);
            }
        }
        return Optional.empty();
    }
}
