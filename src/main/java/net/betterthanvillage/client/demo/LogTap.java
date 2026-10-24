package net.betterthanvillage.client.demo;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The game log as seen by {@code #wait-log}: every log line (client and integrated server) plus the replies of the
 * scene's commands. As in the server tests, a wait only matches lines after the previous match.
 */
final class LogTap {
    private static final int MAX = 20000;
    private static final List<String> LINES = new ArrayList<>();
    private static int dropped;
    private static int cursor;
    private static boolean installed;

    private LogTap() {
    }

    static synchronized void install() {
        if (installed) return;
        installed = true;
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        AbstractAppender appender = new AbstractAppender("BtvDemoTap", null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                add(event.getMessage().getFormattedMessage());
            }
        };
        appender.start();
        context.getConfiguration().getRootLogger().addAppender(appender, Level.INFO, null);
        context.updateLoggers();
    }

    static synchronized void add(String line) {
        LINES.add(line);
        if (LINES.size() > MAX) {
            int cut = LINES.size() - MAX / 2;
            LINES.subList(0, cut).clear();
            dropped += cut;
        }
    }

    /** Only lines logged from now on can match. */
    static synchronized void skipToEnd() {
        cursor = dropped + LINES.size();
    }

    /** The first line after the cursor matching the pattern (and moves the cursor past it), or null. */
    static synchronized String find(Pattern pattern) {
        for (int i = Math.max(cursor, dropped); i < dropped + LINES.size(); i++) {
            String line = LINES.get(i - dropped);
            if (pattern.matcher(line).find()) {
                cursor = i + 1;
                return line;
            }
        }
        return null;
    }
}
