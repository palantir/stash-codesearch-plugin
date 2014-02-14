/**
 * Class that reads a limited number of bytes from an InputStream and converts to source code.
 */

package com.palantir.stash.codesearch.updater;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.atlassian.stash.scm.*;
import com.atlassian.utils.process.*;
import com.atlassian.stash.io.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import com.atlassian.utils.process.Watchdog;

class SourceFileOutputHandler implements CommandOutputHandler<String> {

    private static final int BUF_SIZE = 4096;

    private static final Logger log = LoggerFactory.getLogger(SourceFileOutputHandler.class);

    private static final boolean[] BINARY_BYTES = new boolean[256];

    static {
        for (int i = 0; i <= 8; ++i) {
            BINARY_BYTES[i] = true;
        }
        for (int i = 14; i <= 31; ++i) {
            BINARY_BYTES[i] = true;
        }
        BINARY_BYTES[127] = true;
    }

    private int maxFileSize;

    private String fileContent;

    private Watchdog watchdog;

    public SourceFileOutputHandler (int maxFileSize) {
        this.maxFileSize = maxFileSize;
        fileContent = null;
        watchdog = null;
    }

    private static boolean isBinary (byte b) {
        if (b < 0) {
            return BINARY_BYTES[256 + b];
        }
        return BINARY_BYTES[b];
    }

    @Override
    public void complete () {}

    @Override
    public void process (InputStream is) {
        byte[] buffer = new byte[BUF_SIZE];
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int read;
        try {
            while((read = is.read(buffer)) != -1) {
                if (watchdog != null) {
                    watchdog.resetWatchdog();
                }
                for (int i = 0; i < read; ++i) {
                    if (isBinary(buffer[i])) {
                        return;
                    }
                }
                output.write(buffer, 0, read);
                if (output.size() > maxFileSize) {
                    return;
                }
            }
        } catch (IOException e) {
            log.error("Caught exception while reading source file", e);
        }
        fileContent = output.toString();
    }

    @Override
    public String getOutput () {
        return fileContent;
    }

    @Override
    public void setWatchdog (Watchdog watchdog) {
        this.watchdog = watchdog;
    }

}
