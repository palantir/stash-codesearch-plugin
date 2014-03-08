/**
 * Class that reads a raw string from an InputStream.
 */

package com.palantir.stash.codesearch.updater;

import com.atlassian.stash.scm.*;
import com.atlassian.utils.process.*;
import com.atlassian.stash.io.*;
import java.io.IOException;
import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class StringOutputHandler extends LineReaderOutputHandler implements CommandOutputHandler<String> {

    private static final Logger log = LoggerFactory.getLogger(StringOutputHandler.class);

    private final StringBuilder stringBuilder;

    public StringOutputHandler () {
        super("UTF-8");
        stringBuilder = new StringBuilder();
    }

    @Override
    public String getOutput () {
        return stringBuilder.toString();
    }

    @Override
    public void processReader (LineReader reader) {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
               stringBuilder.append(line + "\n");
            }
        } catch (IOException e) {
            log.error("Caught IOException while reading git command output", e);
        }
    }

}
