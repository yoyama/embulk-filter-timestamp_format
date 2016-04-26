package org.embulk.filter.timestamp_format;

import com.google.common.base.Throwables;
import org.embulk.spi.PageReader;
import org.msgpack.value.ArrayValue;
import org.msgpack.value.MapValue;
import org.msgpack.value.Value;
import org.msgpack.value.ValueFactory;

import org.embulk.filter.timestamp_format.TimestampFormatFilterPlugin.ColumnConfig;
import org.embulk.filter.timestamp_format.TimestampFormatFilterPlugin.PluginTask;

import org.embulk.spi.Column;
import org.embulk.spi.ColumnVisitor;
import org.embulk.spi.Exec;
import org.embulk.spi.PageBuilder;
import org.embulk.spi.time.Timestamp;
import org.embulk.spi.time.TimestampParseException;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ColumnVisitorImpl
        implements ColumnVisitor
{
    private static final Logger logger = Exec.getLogger(TimestampFormatFilterPlugin.class);
    private final PluginTask task;
    private final PageReader pageReader;
    private final PageBuilder pageBuilder;
    private final HashMap<String, TimestampParser> timestampParserMap = new HashMap<String, TimestampParser>();
    private final HashMap<String, TimestampFormatter> timestampFormatterMap = new HashMap<String, TimestampFormatter>();

    ColumnVisitorImpl(PluginTask task, PageReader pageReader, PageBuilder pageBuilder)
    {
        this.task        = task;
        this.pageReader  = pageReader;
        this.pageBuilder = pageBuilder;

        // columnName or jsonPath => TimestampParser
        for (ColumnConfig columnConfig : task.getColumns()) {
            TimestampParser parser = getTimestampParser(columnConfig, task);
            this.timestampParserMap.put(columnConfig.getName(), parser); // NOTE: value would be null
        }
        // columnName or jsonPath => TimestampFormatter
        for (ColumnConfig columnConfig : task.getColumns()) {
            TimestampFormatter parser = getTimestampFormatter(columnConfig, task);
            this.timestampFormatterMap.put(columnConfig.getName(), parser); // NOTE: value would be null
        }
    }

    private TimestampParser getTimestampParser(ColumnConfig columnConfig, PluginTask task)
    {
        DateTimeZone timezone = columnConfig.getFromTimeZone().or(task.getDefaultFromTimeZone());
        List<String> formatList = columnConfig.getFromFormat().or(task.getDefaultFromTimestampFormat());
        return new TimestampParser(task.getJRuby(), formatList, timezone);
    }

    private TimestampFormatter getTimestampFormatter(ColumnConfig columnConfig, PluginTask task)
    {
        String format = columnConfig.getToFormat().or(task.getDefaultToTimestampFormat());
        DateTimeZone timezone = columnConfig.getToTimeZone().or(task.getDefaultToTimeZone());
        return new TimestampFormatter(task.getJRuby(), format, timezone);
    }

    private Value formatTimestampStringRecursively(PluginTask task, String name, Value value)
            throws TimestampParseException
    {
        if (value.isArrayValue()) {
            ArrayValue arrayValue = value.asArrayValue();
            int size = arrayValue.size();
            Value[] newValue = new Value[size];
            for (int i = 0; i < size; i++) {
                String k = new StringBuilder(name).append("[").append(Integer.toString(i)).append("]").toString();
                Value v = arrayValue.get(i);
                newValue[i] = formatTimestampStringRecursively(task, k, v);
            }
            return ValueFactory.newArray(newValue, true);
        }
        else if (value.isMapValue()) {
            MapValue mapValue = value.asMapValue();
            int size = mapValue.size() * 2;
            Value[] newValue = new Value[size];
            int i = 0;
            for (Map.Entry<Value, Value> entry : mapValue.entrySet()) {
                Value k = entry.getKey();
                Value v = entry.getValue();
                String newName = new StringBuilder(name).append(".").append(k.asStringValue().asString()).toString();
                Value r = formatTimestampStringRecursively(task, newName, v);
                newValue[i++] = k;
                newValue[i++] = r;
            }
            return ValueFactory.newMap(newValue, true);
        }
        else if (value.isStringValue()) {
            String stringValue = value.asStringValue().asString();
            String newValue = formatTimestampString(task, name, stringValue);
            return (Objects.equals(newValue, stringValue)) ? value : ValueFactory.newString(newValue);
        }
        else {
            return value;
        }
    }

    private String formatTimestampString(PluginTask task, String name, String value)
            throws TimestampParseException
    {
        TimestampParser parser = timestampParserMap.get(name);
        TimestampFormatter formatter = timestampFormatterMap.get(name);
        if (formatter == null || parser == null) {
            return value;
        }
        try {
            Timestamp timestamp = parser.parse(value);
            return formatter.format(timestamp);
        }
        catch (TimestampParseException ex) {
            if (task.getStopOnInvalidRecord()) {
                throw Throwables.propagate(ex);
            }
            else {
                logger.warn("invalid value \"{}\":\"{}\"", name, value);
                return value;
            }
        }
    }


    @Override
    public void booleanColumn(Column column)
    {
        if (pageReader.isNull(column)) {
            pageBuilder.setNull(column);
        }
        else {
            pageBuilder.setBoolean(column, pageReader.getBoolean(column));
        }
    }

    @Override
    public void longColumn(Column column)
    {
        if (pageReader.isNull(column)) {
            pageBuilder.setNull(column);
        }
        else {
            pageBuilder.setLong(column, pageReader.getLong(column));
        }
    }

    @Override
    public void doubleColumn(Column column)
    {
        if (pageReader.isNull(column)) {
            pageBuilder.setNull(column);
        }
        else {
            pageBuilder.setDouble(column, pageReader.getDouble(column));
        }
    }

    @Override
    public void stringColumn(Column column)
    {
        if (pageReader.isNull(column)) {
            pageBuilder.setNull(column);
            return;
        }
        String value = pageReader.getString(column);
        String formatted = formatTimestampString(task, column.getName(), value);
        pageBuilder.setString(column, formatted);
    }

    @Override
    public void jsonColumn(Column column)
    {
        if (pageReader.isNull(column)) {
            pageBuilder.setNull(column);
        }
        else {
            String name = new StringBuilder("$.").append(column.getName()).toString();
            Value value = pageReader.getJson(column);
            Value formatted = formatTimestampStringRecursively(task, name, value);
            pageBuilder.setJson(column, formatted);
        }
    }

    @Override
    public void timestampColumn(Column column)
    {
        if (pageReader.isNull(column)) {
            pageBuilder.setNull(column);
        }
        else {
            pageBuilder.setTimestamp(column, pageReader.getTimestamp(column));
        }
    }
}
