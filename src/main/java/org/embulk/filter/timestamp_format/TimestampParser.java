package org.embulk.filter.timestamp_format;

import com.google.common.base.Optional;

import org.embulk.config.Config;
import org.embulk.config.ConfigDefault;

import org.embulk.filter.timestamp_format.TimestampFormatFilterPlugin.PluginTask;

import org.embulk.spi.time.JRubyTimeParserHelper;
import org.embulk.spi.time.JRubyTimeParserHelperFactory;
import org.embulk.spi.time.Timestamp;

import static org.embulk.spi.time.TimestampFormat.parseDateTimeZone;

import org.embulk.spi.time.TimestampParseException;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormatter;
import org.jruby.embed.ScriptingContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.joda.time.format.DateTimeFormat;

public class TimestampParser {
    public interface Task {
        @Config("default_from_timezone")
        @ConfigDefault("\"UTC\"")
        DateTimeZone getDefaultFromTimeZone();

        @Config("default_from_timestamp_format")
        @ConfigDefault("[\"%Y-%m-%d %H:%M:%S.%N %z\"]")
        List<String> getDefaultFromTimestampFormat();
    }

    public interface TimestampColumnOption {
        @Config("from_timezone")
        @ConfigDefault("null")
        Optional<DateTimeZone> getFromTimeZone();

        @Config("from_format")
        @ConfigDefault("null")
        Optional<List<String>> getFromFormat();
    }

    private final List<JRubyTimeParserHelper> jrubyParserList = new ArrayList<>();
    private final List<DateTimeFormatter> javaParserList = new ArrayList<>();
    private final List<UnixTimeParser> unixTimeParserList = new ArrayList<>();
    private final List<Boolean> handleNanoResolutionList = new ArrayList<>();
    private final DateTimeZone defaultFromTimeZone;
    private final Pattern nanoSecPattern = Pattern.compile("\\.(\\d+)");

    TimestampParser(PluginTask task) {
        this(task.getJRuby(), task.getDefaultFromTimestampFormat(), task.getDefaultFromTimeZone());
    }

    public TimestampParser(PluginTask task, TimestampColumnOption columnOption) {
        this(task.getJRuby(),
                columnOption.getFromFormat().or(task.getDefaultFromTimestampFormat()),
                columnOption.getFromTimeZone().or(task.getDefaultFromTimeZone()));
    }

    public TimestampParser(ScriptingContainer jruby, List<String> formatList, DateTimeZone defaultFromTimeZone) {
        JRubyTimeParserHelperFactory helperFactory = (JRubyTimeParserHelperFactory) jruby.runScriptlet("Embulk::Java::TimeParserHelper::Factory.new");

        // TODO get default current time from ExecTask.getExecTimestamp
        for (String format : formatList) {
            if (format.contains("%")) {
                JRubyTimeParserHelper helper = (JRubyTimeParserHelper) helperFactory.newInstance(format, 1970, 1, 1, 0, 0, 0, 0);  // TODO default time zone
                this.jrubyParserList.add(helper);
            } else if(format.equals("UNIX_TIME_DAY")){
                this.unixTimeParserList.add(getUnixTimeParser());
            } else {
                // special treatment for nano resolution. n is not originally supported by Joda-Time
                if (format.contains("nnnnnnnnn")) {
                    this.handleNanoResolutionList.add(true);
                    String newFormat = format.replaceAll("n", "S");
                    DateTimeFormatter parser = DateTimeFormat.forPattern(newFormat).withLocale(Locale.ENGLISH).withZone(defaultFromTimeZone);
                    this.javaParserList.add(parser);
                }
                else {
                    this.handleNanoResolutionList.add(false);
                    DateTimeFormatter parser = DateTimeFormat.forPattern(format).withLocale(Locale.ENGLISH).withZone(defaultFromTimeZone);
                    this.javaParserList.add(parser);
                }
            }
        }
        this.defaultFromTimeZone = defaultFromTimeZone;
    }

    private UnixTimeParser getUnixTimeParser() {
        return new UnixTimeParser();
    }

    public DateTimeZone getDefaultFromTimeZone() {
        return defaultFromTimeZone;
    }

    public Timestamp parse(String text) throws TimestampParseException, IllegalArgumentException {
        if (!jrubyParserList.isEmpty()) {
            return jrubyParse(text);
        } else if (!javaParserList.isEmpty()) {
            return javaParse(text);
        } else if (!unixTimeParserList.isEmpty()) {
            return unixTitmeParse(text);
        } else {
            assert false;
            throw new RuntimeException();
        }
    }

    private Timestamp jrubyParse(String text) throws TimestampParseException {
        long localUsec = -1;
        TimestampParseException exception = null;

        JRubyTimeParserHelper helper = null;
        for (JRubyTimeParserHelper h : jrubyParserList) {
            helper = h;
            try {
                localUsec = helper.strptimeUsec(text); // NOTE: micro second resolution
                break;
            } catch (TimestampParseException ex) {
                exception = ex;
            }
        }
        if (localUsec == -1) {
            throw exception;
        }
        DateTimeZone timeZone = defaultFromTimeZone;
        String zone = helper.getZone();

        if (zone != null) {
            // TODO cache parsed zone?
            timeZone = parseDateTimeZone(zone);
            if (timeZone == null) {
                throw new TimestampParseException("Invalid time zone name '" + text + "'");
            }
        }

        long localSec = localUsec / 1000000;
        long usec = localUsec % 1000000;
        long sec = timeZone.convertLocalToUTC(localSec * 1000, false) / 1000;

        return Timestamp.ofEpochSecond(sec, usec * 1000);
    }

    private Timestamp javaParse(String text) throws IllegalArgumentException {
        long msec = -1;
        long nsec = -1;
        Boolean handleNanoResolution = false;
        IllegalArgumentException exception = null;

        for (int i = 0; i < javaParserList.size(); i++) {
            DateTimeFormatter parser = javaParserList.get(i);
            handleNanoResolution = handleNanoResolutionList.get(i);
            try {
                if (handleNanoResolution) {
                    nsec = parseNano(text);
                }
                DateTime dateTime = parser.parseDateTime(text);
                msec = dateTime.getMillis(); // NOTE: milli second resolution
                break;
            } catch (IllegalArgumentException ex) {
                exception = ex;
            }
        }
        if (msec == -1) {
            throw exception;
        }

        if (handleNanoResolution) {
            long sec = msec / 1000;
            return Timestamp.ofEpochSecond(sec, nsec);
        }
        else {
            long nanoAdjustment = msec * 1000000;
            return Timestamp.ofEpochSecond(0, nanoAdjustment);
        }
    }

    private Timestamp unixTitmeParse(String text) throws IllegalArgumentException {
        long nsec = -1;
        IllegalArgumentException exception = null;

        for (int i = 0; i < unixTimeParserList.size(); i++) {
            UnixTimeParser parser = unixTimeParserList.get(i);
            try {
                nsec = parser.parse(text);
                break;
            } catch (IllegalArgumentException ex) {
                exception = ex;
            }
        }

        if(exception != null){
            throw exception;
        }
        return Timestamp.ofEpochSecond(0, nsec);
    }

    private long parseNano(String text) {
        long nsec = -1;
        Matcher m = nanoSecPattern.matcher(text);
        if (m.find()) {
            //String nanoStr = String.format("%-9s", m.group(1)).replace(" ", "0");
            //nsec = Long.parseLong(nanoStr);
            String nanoStr = m.group(1);
            nsec = Long.parseLong(nanoStr) * (long) Math.pow(10, 9 - nanoStr.length());
        }
        return nsec;
    }
}
