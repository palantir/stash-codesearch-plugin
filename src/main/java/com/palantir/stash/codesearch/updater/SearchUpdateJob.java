/**
 * Interface for executing updates over a specific branch.
 */

package com.palantir.stash.codesearch.updater;

import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.scm.git.GitScm;
import com.palantir.stash.codesearch.admin.GlobalSettings;
import org.elasticsearch.client.Client;

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
     * Executes an incremental update with the specified SCM manager and elasticsearch client.
     */
    void doUpdate (Client client, GitScm gitScm, GlobalSettings globalSettings);

    /**
     * Executes a full update with the specified SCM manager and elasticsearch client. Note that
     * during the reindex, search will not be available for this branch. If availability is needed,
     * use the reindexAll() method of SearchUpdater.
     */
    void doReindex (Client client, GitScm gitScm, GlobalSettings globalSettings);

}
