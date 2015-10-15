/**
 * Class that reads batch output from cat-file.
 */

package com.palantir.stash.codesearch.updater;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;

import org.slf4j.Logger;

import com.atlassian.bitbucket.scm.CommandOutputHandler;
import com.atlassian.utils.process.Watchdog;
import com.palantir.stash.codesearch.logger.PluginLoggerFactory;

class CatFileOutputHandler implements CommandOutputHandler<String[]> {

    private final Logger log;

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

    private static boolean isBinary(byte b) {
        if (b < 0) {
            return BINARY_BYTES[256 + b];
        }
        return BINARY_BYTES[b];
    }

    private final Collection<Integer> fileSizes;

    private Collection<String> outputFiles;

    private int maxFileSize;

    // TODO: unused
    private Watchdog watchdog;

    public CatFileOutputHandler(PluginLoggerFactory plf) {
        this(plf, new ArrayList<Integer>());
    }

    public CatFileOutputHandler(PluginLoggerFactory plf, Collection<Integer> fileSizes) {
        this.log = plf.getLogger(this.getClass().toString());
        this.fileSizes = fileSizes;
        this.outputFiles = new ArrayList<String>();
        this.maxFileSize = 0;
    }

    public boolean addFile(int fileSize) {
        if (fileSizes.add(fileSize)) {
            maxFileSize = Math.max(maxFileSize, fileSize);
        }
        return false;
    }

    @Override
    public String[] getOutput() {
        return outputFiles == null ? null : outputFiles.toArray(new String[outputFiles.size()]);
    }

    @Override
    public void complete() {
    }

    @Override
    public void setWatchdog(Watchdog watchdog) {
        this.watchdog = watchdog;
    }

    @Override
    public void process(InputStream is) {
        byte[] buffer = new byte[maxFileSize + 2];
        try {
            for (int fileSize : fileSizes) {
                if (watchdog != null) {
                    watchdog.resetWatchdog();
                }

                // read fileSize bytes
                is.read(); // clear newline
                int offset = 0;
                while ((offset += is.read(buffer, offset, fileSize - offset)) < fileSize)
                    ;
                is.read(); // clear newline

                // Check for binary bytes
                boolean binary = false;
                for (int i = 0; i < fileSize; ++i) {
                    if (isBinary(buffer[i])) {
                        binary = true;
                        break;
                    }
                }

                // Add output string
                if (binary) {
                    outputFiles.add(null);
                } else {
                    outputFiles.add(new String(buffer, 0, fileSize));
                }
            }
        } catch (IOException e) {
            log.error("Error reading output from cat-file, aborting", e);
            outputFiles = null;
        }
    }

}
