/**
 * Interface for executing updates over a specific branch.
 */

package com.palantir.stash.codesearch.updater;

import com.atlassian.stash.repository.Branch;
import com.atlassian.stash.repository.Repository;
import com.atlassian.stash.scm.git.GitScm;

public interface SearchUpdateJob {

    /**
     * Returns this job's repository.
     */
    Repository getRepository ();

    /**
     * Returns this job's branch.
     */
    Branch getBranch ();

    /**
     * Executes an incremental update with the specified SCM manager.
     */
    void doUpdate (GitScm gitScm);

    /**
     * Executes a full update with the specified SCM manager. Note that during the reindex, search
     * will not be available for this branch. If availability is needed, use the reindexAll()
     * method of SearchUpdater.
     */
    void doReindex (GitScm gitScm);

}
