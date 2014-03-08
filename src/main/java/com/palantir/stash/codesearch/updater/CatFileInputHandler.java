/**
 * Class that feeds object hashes to git cat-file.
 */

package com.palantir.stash.codesearch.updater;

import com.atlassian.stash.scm.CommandInputHandler;
import com.atlassian.utils.process.Watchdog;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class CatFileInputHandler implements CommandInputHandler {

    private static final Logger log = LoggerFactory.getLogger(CatFileInputHandler.class);

    private final Collection<String> objects;

    private Watchdog watchdog;

    public CatFileInputHandler () {
        this.objects = new ArrayList<String>();
    }

    public CatFileInputHandler (Collection<String> objects) {
        this.objects = objects;
    }

    public boolean addObject (String object) {
        return objects.add(object);
    }

    @Override
    public void complete () {}

    @Override
    public void process (OutputStream os) {
        try {
            for (String object : objects) {
                os.write(object.getBytes());
                os.write('\n');
                if (watchdog != null) {
                    watchdog.resetWatchdog();
                }
            }
            os.close();
        } catch (IOException e) {
            log.error("Error feeding input into cat-file, aborting", e);
        }
    }

    @Override
    public void setWatchdog (Watchdog watchdog) {
        this.watchdog = watchdog;
    }

}
