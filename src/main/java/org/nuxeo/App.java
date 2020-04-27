/*
 * (C) Copyright 2013 Nuxeo SAS <http://nuxeo.com>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2 as published
 * by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA
 * 02111-1307, USA.
 *
 * Author: bdelbosc@nuxeo.com
 * Improvements by: alfonso777@github
 *
 */
package org.nuxeo;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Timer;
import com.yammer.metrics.core.TimerContext;
import com.yammer.metrics.reporting.ConsoleReporter;

/**
 * Test jdbc connection and network latency
 *
 */
public class App {

    private static final Log log = LogFactory.getLog(App.class);

    private final static Timer connTimer = Metrics.defaultRegistry().newTimer(
            App.class, "connection", TimeUnit.MILLISECONDS, TimeUnit.SECONDS);

    private final static Timer execTimer = Metrics.defaultRegistry().newTimer(
            App.class, "execution", TimeUnit.MILLISECONDS, TimeUnit.SECONDS);

    private final static Timer fetchingTimer = Metrics.defaultRegistry().newTimer(
            App.class, "fetching", TimeUnit.MILLISECONDS, TimeUnit.SECONDS);

    private static final String CONFIG_KEY = "config";

    private static final String DEFAULT_CONFIG_FILE = "jdbctester.properties";

    private static final String REPEAT_KEY = "repeat";

    private static final String DEFAULT_REPEAT = "100";

    private static final Integer QUERY_SELECT_LIMIT = 137;

    public static void main(String[] args) throws SQLException, IOException {

        Properties prop = Config.readProperties(CONFIG_KEY, DEFAULT_CONFIG_FILE);
        String user = prop.getProperty("user");
        String password = prop.getProperty("password");
        String connectionURL = prop.getProperty("url");
        String driver = prop.getProperty("driver");
        String query = prop.getProperty("query");

        log.info("Connect to:" + connectionURL + " from " + getHostName());
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream printStream = new PrintStream(baos);
        final ConsoleReporter reporter = new ConsoleReporter(printStream);

        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        TimerContext tc = null;
        int repeat = Integer.valueOf(
                System.getProperty(REPEAT_KEY, DEFAULT_REPEAT)).intValue();

        if(query.toLowerCase().startsWith("select"))
            query = query.replaceAll(";", "") + " LIMIT " + QUERY_SELECT_LIMIT.toString();
    
        log.info("Submiting " + repeat + " queries: " + query);
        try {
            Class.forName(driver);
            tc = connTimer.time();
            conn = DriverManager.getConnection(connectionURL, user, password);
            tc.stop();
            ps = conn.prepareStatement(query,
                    ResultSet.TYPE_SCROLL_INSENSITIVE,
                    ResultSet.CONCUR_READ_ONLY);
            
            int paramCount = countOccurrences(query, '?');
            for (int i = 1; i <= paramCount; i++) {
                String key = "p" + i;
                String param = prop.getProperty(key);
                if (param == null) {
                    break;
                }
                log.info(key + " = " + param);
                String type = "object";
                if (param.contains(":")) {
                    type = param.split(":", 2)[0];
                    param = param.split(":", 2)[1];
                }
                if (type.equalsIgnoreCase("object")) {
                    ps.setObject(i, (Object) param);
                } else if (type.equalsIgnoreCase("string")) {
                    ps.setString(i, param);
                } else if (type.equalsIgnoreCase("nstring")) {
                    ps.setNString(i, param);
                } else {
                    log.warn("Unknown type " + type + " use setObject");
                    ps.setObject(i, (Object) param);
                }
            }

            int rows = 0;
            int bytes = 0;

            for (int i = 0; i < repeat; i++) {
                tc = execTimer.time();
                rs = ps.executeQuery();
                tc.stop();
                tc = fetchingTimer.time();
                ResultSetMetaData rsmd = rs.getMetaData();
                Integer cols = rsmd.getColumnCount();
                String header = IntStream.range(1,cols+1).mapToObj(index -> getHeaderColumnValue(rsmd,index)).collect(Collectors.joining("\t"));
                log.info("Header: [" + header + "]");
                log.info("==========Result===========");
                while (rs.next()) {
                    rows++;
                    for (int c = 1; c <= cols; c++) {
                        bytes += rs.getBytes(1).length;
                    }

                    if(repeat == 1 ) {
                        final ResultSet rsf = rs;
                        String fullRow = IntStream.range(1,cols+1).mapToObj(index -> getValue(rsf, index)).collect(Collectors.joining("\t"));
                        log.info(fullRow);
                    }
                    
                    
                    
                }
                rs.close();
                tc.stop();
                // don't stress too much
                Thread.sleep((int) (Math.random() * 100));
            }
            log.info("\nFetched rows: " + rows + ", total bytes: " + bytes
                    + ", bytes/rows: " + ((float) bytes) / rows);

        } catch (SQLException e) {
            log.error(e.getMessage(), e);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        } finally {
            if (rs != null) {
                rs.close();
            }
            if (ps != null) {
                ps.close();
            }
            if (conn != null) {
                conn.close();
            }
        }
        reporter.run();
        try {
            String content = baos.toString("ISO-8859-1");
            log.info(content);
        } catch (UnsupportedEncodingException e) {
            log.error(e.getMessage(), e);
        }

    }

    private static String getHostName() {
        String hostname;
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            hostname = "unknown";
        }
        return hostname;
    }

    private static int countOccurrences(String haystack, char needle) {
        int count = 0;
        for (int i = 0; i < haystack.length(); i++) {
            if (haystack.charAt(i) == needle) {
                count++;
            }
        }
        return count;
    }

    private static String getValue(ResultSet rs, int columnIndex) {
        try {
            Object value = rs.getObject(columnIndex);
            return rs.wasNull() ? "null" : value.toString();
        } catch (SQLException e) {
            return "ERROR";
        }
    }
    
    private static String getHeaderColumnValue(ResultSetMetaData rsmd, int index) {
        try {
            Object value = rsmd.getColumnName(index);
            return value == null ? "null" : value.toString();
        } catch (SQLException e) {
            return "ERROR";
        }
    }
}
