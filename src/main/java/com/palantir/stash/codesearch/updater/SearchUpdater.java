/**
 * Interface for incremental updates of the search index. Must be stopped during full reindexing.
 * To facilitate both incremental updates and full reindexes, implementations should maintain two
 * job queues -- one for branch reindexes, and another for branch updates (triggered by hooks and merge checks).
 */

package com.palantir.stash.codesearch.updater;

import com.atlassian.stash.repository.Repository;
import java.util.concurrent.Future;

public interface SearchUpdater {

    /**
     * Submits a repository and branch to be asynchronously updated after a delay of at least
     * delayMs.
     */
    Future submitAsyncUpdate (Repository repository, String ref, int delayMs);

    /**
     * Submits a repository and branch to be asynchronously reindexed after a delay of at least
     * delayMs.
     */
    Future submitAsyncReindex (Repository repository, String ref, int delayMs);

    /**
     * Submits a repository and branch to be updated after a delay of at least delayMs. Blocks
     * until the update job has completed.
     */
    void submitUpdate (Repository repository, String ref, int delayMs);

    /**
     * Submits a repository and branch to be reindexed after a delay of at least delayMs. Blocks
     * until the reindex job has completed.
     */
    void submitReindex (Repository repository, String ref, int delayMs);

    /**
     * Triggers a full reindex of all branches. Implementations should perform ailasing to ensure
     * availability during the reindex. Returns false if a full reindex is already running.
     * Blocks until the reindex job has completed.
     */
    boolean reindexAll ();

    /**
     * Refresh the maximum number of concurrent indexing jobs based on the global settings manager.
     */
    void refreshConcurrencyLimit ();

}
