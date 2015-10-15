/**
 * Ref change/deletion listener for updating search index on pushes to Stash.
 */

package com.palantir.stash.codesearch.event;

import com.atlassian.event.api.EventListener;
import com.atlassian.bitbucket.event.repository.RepositoryDeletedEvent;
import com.atlassian.bitbucket.event.repository.RepositoryRefsChangedEvent;
import com.atlassian.bitbucket.repository.RefChange;
import com.atlassian.bitbucket.repository.RefChangeType;
import com.atlassian.bitbucket.repository.Repository;
import com.palantir.stash.codesearch.repository.RepositoryServiceManager;
import com.palantir.stash.codesearch.updater.SearchUpdater;

public class IndexerEventListener {

    private final RepositoryServiceManager repositoryServiceManager;

    private final SearchUpdater updater;

    public IndexerEventListener(
        RepositoryServiceManager repositoryServiceManager, SearchUpdater updater) {
        this.repositoryServiceManager = repositoryServiceManager;
        this.updater = updater;
    }

    @EventListener
    public void refChangeListener(RepositoryRefsChangedEvent event) {
        Repository repository = event.getRepository();
        for (RefChange change : event.getRefChanges()) {
            if (change.getType() == RefChangeType.DELETE) {
                updater.submitAsyncReindex(repository, change.getRefId(), 0);
            } else if (repositoryServiceManager.getBranchMap(repository)
                .containsKey(change.getRefId())) {
                updater.submitAsyncUpdate(repository, change.getRefId(), 0);
            }
        }
    }

    @EventListener
    public void repositoryDeletedListener(RepositoryDeletedEvent event) {
        updater.reindexRepository(
            event.getRepository().getProject().getKey(), event.getRepository().getSlug());
    }

}
