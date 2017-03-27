package org.embulk.filter.timestamp_format;

import org.embulk.EmbulkTestRuntime;
import org.embulk.spi.time.Timestamp;
import org.joda.time.DateTimeZone;
import org.jruby.embed.ScriptingContainer;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TestUnixTimeParser {
    @Rule
    public EmbulkTestRuntime runtime = new EmbulkTestRuntime();
    public ScriptingContainer jruby;
    public DateTimeZone zone;
    public Timestamp timestamp;

    @Before
    public void createResource()
    {
        jruby = new ScriptingContainer();
        //zone = DateTimeZone.UTC;
        //timestamp = Timestamp.ofEpochSecond(1463130159, 123456789);
    }

    @Test
    public void testUnixTimeDay() {
        Long nano = 1000000000L;
        TimestampParser unixTimeParser = new TimestampParser(jruby, Arrays.asList("UNIX_TIME_DAY"), zone);

        List<String> data = Arrays.asList("1", "15399", "17257"); // 1970.01.02, 2012.02.29, 2017.04.01
        List<Timestamp> expected = Arrays.asList(
                Timestamp.ofEpochSecond(86400L, 0L),
                Timestamp.ofEpochSecond(1330473600L, 0L),
                Timestamp.ofEpochSecond(1491004800L, 0));
        for (int i = 0; i < data.size(); i++) {
            assertEquals(expected.get(i), unixTimeParser.parse(data.get(i)));
        }
    }
}
