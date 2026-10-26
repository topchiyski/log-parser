package logparser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads raw log lines for the command-line programs: either the sample logs
 * bundled under {@code src/main/resources/logs/}, or files named on the
 * command line.
 */
final class LogSources {

    private static final Logger LOG = LoggerFactory.getLogger(LogSources.class);

    /**
     * Classpath resources used when no file is given. application.log
     * deliberately contains four malformed lines; incident.log contains
     * repeated errors and two error spikes.
     */
    static final List<String> BUNDLED_LOGS =
            List.of("logs/application.log", "logs/incident.log");

    private LogSources() {
        // Not instantiable: static helpers only.
    }

    /**
     * Loads the bundled logs if {@code paths} is empty, otherwise the named
     * files. A file that can't be read is logged at ERROR and left out.
     *
     * @param paths file paths, as given on the command line
     * @return an unmodifiable map from source name to its lines, in order
     */
    static Map<String, List<String>> load(List<String> paths) {
        Map<String, List<String>> logs = new LinkedHashMap<>();
        if (paths.isEmpty()) {
            for (String resource : BUNDLED_LOGS) {
                logs.put(resource, readResource(resource));
            }
        }
        for (String path : paths) {
            try {
                logs.put(path, Files.readAllLines(Path.of(path), StandardCharsets.UTF_8));
            } catch (IOException | InvalidPathException e) {
                LOG.error("Could not read {}", path, e);
            }
        }
        return Collections.unmodifiableMap(logs);
    }

    /**
     * Reads a classpath resource as UTF-8 lines. Reading from the classpath
     * rather than the file system means it also works from the packaged jar.
     *
     * @param name the resource path, e.g. "logs/application.log"
     * @return the lines of the resource, without line terminators
     * @throws IllegalStateException if the resource doesn't exist
     */
    static List<String> readResource(String name) {
        InputStream stream = LogSources.class.getClassLoader().getResourceAsStream(name);
        if (stream == null) {
            throw new IllegalStateException("Resource not found: " + name);
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return reader.lines().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read resource " + name, e);
        }
    }
}
