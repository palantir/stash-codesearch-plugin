/**
 * Class that reads a raw string from an InputStream.
 */

package com.palantir.stash.codesearch.updater;

import java.io.IOException;

import org.slf4j.Logger;

import com.atlassian.stash.io.LineReader;
import com.atlassian.stash.io.LineReaderOutputHandler;
import com.atlassian.stash.scm.CommandOutputHandler;
import com.atlassian.utils.process.Watchdog;
import com.palantir.stash.codesearch.logger.PluginLoggerFactory;

class StringOutputHandler extends LineReaderOutputHandler implements CommandOutputHandler<String> {

    private final StringBuilder stringBuilder;
    private final Logger log;
    private Watchdog watchdog;

    public StringOutputHandler(PluginLoggerFactory plf) {
        super("UTF-8");
        this.log = plf.getLogger(this.getClass().toString());
        stringBuilder = new StringBuilder();
    }

    @Override
    public String getOutput() {
        return stringBuilder.toString();
    }

    @Override
    public void setWatchdog(Watchdog watchdog) {
        this.watchdog = watchdog;
    }

    @Override
    public void processReader(LineReader reader) {
        try {
            String line;
            if (watchdog != null) {
                watchdog.resetWatchdog();
            }
            while ((line = reader.readLine()) != null) {
                stringBuilder.append(line + "\n");
            }
        } catch (IOException e) {
            log.error("Caught IOException while reading git command output", e);
        }
    }

}
