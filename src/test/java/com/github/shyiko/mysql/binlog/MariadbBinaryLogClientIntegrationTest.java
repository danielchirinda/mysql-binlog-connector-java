package com.github.shyiko.mysql.binlog;

import com.github.shyiko.mysql.binlog.event.AnnotateRowsEventData;
import com.github.shyiko.mysql.binlog.event.MariadbGtidEventData;
import com.github.shyiko.mysql.binlog.event.deserialization.EventDeserializer;
import org.testng.SkipException;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertNotEquals;
import static org.testng.AssertJUnit.assertNotNull;

/**
 * @author <a href="mailto:winger2049@gmail.com">Winger</a>
 */
public class MariadbBinaryLogClientIntegrationTest extends BinaryLogClientIntegrationTest {

    MysqlOnetimeServer primaryServer;
    protected BinaryLogClientIntegrationTest.MySQLConnection master;

    @Test
    public void testMariadbUseGTIDAndAnnotateRowsEvent() throws Exception {
        if ( !mysqlVersion.isMaria )
            throw new SkipException("not maria");


        master.execute(new BinaryLogClientIntegrationTest.Callback<Statement>() {
            @Override
            public void execute(Statement statement) throws SQLException {
                statement.execute("drop database if exists mbcj_test");
                statement.execute("create database mbcj_test");
                statement.execute("use mbcj_test");
                statement.execute("CREATE TABLE if not exists foo (i int)");
                statement.execute("CREATE TABLE if not exists bar (i int)");
            }
        });
        // get current gtid
        final String[] currentGtidPos = new String[1];
        master.query("show global variables like 'gtid_current_pos%'", new BinaryLogClientIntegrationTest.Callback<ResultSet>() {

            @Override
            public void execute(ResultSet rs) throws SQLException {
                rs.next();
                currentGtidPos[0] = rs.getString(2);
            }
        });

        CountDownEventListener eventListener;
        MariadbBinaryLogClient client = new MariadbBinaryLogClient(master.hostname(), master.port(), master.username(), master.password());
        client.setGtidSet(currentGtidPos[0]);
        client.setUseSendAnnotateRowsEvent(true);

        EventDeserializer eventDeserializer = new EventDeserializer();
        eventDeserializer.setCompatibilityMode(EventDeserializer.CompatibilityMode.CHAR_AND_BINARY_AS_BYTE_ARRAY,
            EventDeserializer.CompatibilityMode.DATE_AND_TIME_AS_LONG);
        client.setEventDeserializer(eventDeserializer);
        client.registerEventListener(new TraceEventListener());
        client.registerLifecycleListener(new TraceLifecycleListener());
        client.registerEventListener(eventListener = new CountDownEventListener());

        master.execute(new BinaryLogClientIntegrationTest.Callback<Statement>() {
            @Override
            public void execute(Statement statement) throws SQLException {
                statement.execute("INSERT INTO foo set i = 2");
                statement.execute("DROP TABLE IF EXISTS bar");
            }
        });

        try {
            eventListener.reset();
            client.connect();

            eventListener.waitFor(MariadbGtidEventData.class, 1, TimeUnit.SECONDS.toMillis(4));
            String gtidSet = client.getGtidSet();
            assertNotNull(gtidSet);

            eventListener.reset();
            eventListener.waitFor(AnnotateRowsEventData.class, 1, TimeUnit.SECONDS.toMillis(4));
            gtidSet = client.getGtidSet();
            assertNotEquals(currentGtidPos[0], gtidSet);
        } finally {
            client.disconnect();
        }
    }
}