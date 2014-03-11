/**
 * Hook for updating search index on pushes to Stash.
 */

package com.palantir.stash.codesearch.hook;

import com.atlassian.stash.hook.*;
import com.atlassian.stash.repository.*;
import com.palantir.stash.codesearch.repository.RepositoryServiceManager;
import com.palantir.stash.codesearch.updater.SearchUpdater;
import java.util.Collection;

public class PostReceiveUpdaterHook implements PostReceiveHook {

    private final RepositoryServiceManager repositoryServiceManager;

    private final SearchUpdater updater;

    public PostReceiveUpdaterHook (
            RepositoryServiceManager repositoryServiceManager, SearchUpdater updater) {
        this.repositoryServiceManager = repositoryServiceManager;
        this.updater = updater;
    }

    @Override
    public void onReceive (Repository repository, Collection<RefChange> refChanges,
            HookResponse resp) {
        for (RefChange change : refChanges) {
            if (change.getType() == RefChangeType.DELETE) {
                updater.submitAsyncReindex(repository, change.getRefId(), 0);
            } else if (repositoryServiceManager.getBranchMap(repository)
                    .containsKey(change.getRefId())) {
                updater.submitAsyncUpdate(repository, change.getRefId(), 0);
            }
        }
    }

}
