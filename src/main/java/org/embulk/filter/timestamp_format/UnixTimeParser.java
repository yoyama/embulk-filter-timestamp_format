package org.embulk.filter.timestamp_format;


import org.joda.time.DateTime;

public class UnixTimeParser {
    public UnixTimeParser(){}

    public long parse(String text){
        return parseAsDay(text);
    }

    public long parseAsDay(String text) {
        int days = Integer.parseInt(text);
        DateTime epoch = new DateTime(0);
        return epoch.plusDays(days).getMillis() * 1000000;
    }
}
