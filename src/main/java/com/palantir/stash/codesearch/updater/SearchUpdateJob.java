/**
 * Interface for executing updates over a specific branch.
 */

package com.palantir.stash.codesearch.updater;

import com.atlassian.stash.repository.Repository;
import com.atlassian.stash.scm.git.GitScm;
import com.palantir.stash.codesearch.admin.GlobalSettings;

public interface SearchUpdateJob {

    /**
     * Returns this job's repository.
     */
    Repository getRepository ();

    /**
     * Returns this job's ref.
     */
    String getRef ();

    /**
     * Executes an incremental update with the specified SCM manager.
     */
    void doUpdate (GitScm gitScm, GlobalSettings globalSettings);

    /**
     * Executes a full update with the specified SCM manager. Note that during the reindex, search
     * will not be available for this branch. If availability is needed, use the reindexAll()
     * method of SearchUpdater.
     */
    void doReindex (GitScm gitScm, GlobalSettings globalSettings);

}
