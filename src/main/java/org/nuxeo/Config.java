package org.nuxeo;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Properties;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class Config {
    private static final Log log = LogFactory.getLog(App.class);

    public static Properties readProperties(String configKey, String defaultConfigFile) throws IOException {
        Properties prop = new Properties();
        FileInputStream fs;
        try {
            fs = new FileInputStream(System.getProperty(configKey));
        } catch (FileNotFoundException e) {
            log.error(
                    "Property file not found: "
                            + System.getProperty(configKey, configKey), e);
            throw e;
        }
        try {
            prop.load(fs);
            fs.close();
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        } catch (NullPointerException e) {
            log.error("File not found " + defaultConfigFile, e);
        }
        return prop;
    }
}