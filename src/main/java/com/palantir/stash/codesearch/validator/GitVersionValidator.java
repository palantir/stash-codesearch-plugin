package com.palantir.stash.codesearch.validator;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import org.slf4j.Logger;

import com.atlassian.bitbucket.scm.Command;
import com.atlassian.bitbucket.scm.CommandOutputHandler;
import com.atlassian.bitbucket.scm.git.command.GitCommandBuilderFactory;
import com.atlassian.bitbucket.scm.git.GitScm;
import com.atlassian.utils.process.ProcessException;
import com.atlassian.utils.process.Watchdog;
import com.google.common.io.LineReader;
import com.palantir.stash.codesearch.logger.PluginLoggerFactory;

/**
 * This component should be instantiated when the plugin is started up, allowing us to fail the plugin activation of the
 * version of git is detected to be incompatible.
 *
 * @author cmyers
 *
 */
public class GitVersionValidator {

    private static final Integer[] REQUIRED_GIT_VERSION = { 1, 8, 0, 0 };
    private static final String REQUIRED_GIT_VERSION_STRING = "1.8.0.0";

    private final Logger log;

    public GitVersionValidator(GitScm gitScm, PluginLoggerFactory plf) {
        this.log = plf.getLogger(this.getClass().toString());
        log.info("Testing Git Version");
        try {
            String gitVersion = validateVersion(gitScm);
            log.info("Determined Git Version OK! (" + gitVersion + ")");
        } catch (Exception e) {
            log.error("Failed to verify git version", e);
            throw e;
        }
    }

    private String validateVersion(GitScm gitScm) {
        GitCommandBuilderFactory gcbf = gitScm.getCommandBuilderFactory();
        CommandOutputHandler<String> coh = new CommandOutputHandler<String>() {

            private final StringBuilder sb = new StringBuilder();
            private boolean finished = false;

            @Override
            public void process(InputStream output) throws ProcessException {
                final LineReader lr = new LineReader(new InputStreamReader(output));
                String line = null;
                try {
                    while ((line = lr.readLine()) != null) {
                        sb.append(line);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Error while determining git version:", e);
                }
            }

            @Override
            public void complete() throws ProcessException {
                finished = true;
            }

            @Override
            public void setWatchdog(Watchdog watchdog) {
            }

            @Override
            public String getOutput() {
                if (!finished) {
                    throw new IllegalStateException("Called getOutput() before complete()");
                }
                return sb.toString();
            }
        };
        Command<String> versionCommand = gcbf.builder().version(coh);
        versionCommand.call();
        String versionString = coh.getOutput();
        // version string looks like this: git version 1.7.9.5
        String versionNumber = versionString.split(" ")[2];
        String[] versionParts = versionNumber.split("\\.");

        int min =
            (versionParts.length <= REQUIRED_GIT_VERSION.length) ? versionParts.length : REQUIRED_GIT_VERSION.length;

        for (int i = 0; i < min; ++i) {
            try {
                int test = Integer.parseInt(versionParts[i]);
                if (test > REQUIRED_GIT_VERSION[i]) {
                    return versionNumber;
                } else if (test < REQUIRED_GIT_VERSION[i]) {
                    throw new IllegalStateException("Invalid Git Version '" + versionNumber
                        + "', must be equal to or greater than '" + REQUIRED_GIT_VERSION_STRING + "'");
                }
                // else: keep checking next number
            } catch (NumberFormatException e) {
                throw new RuntimeException("Error while determining git version:", e);
            }
        }
        // if we get here, we had a perfect match
        return versionNumber;
    }
}
