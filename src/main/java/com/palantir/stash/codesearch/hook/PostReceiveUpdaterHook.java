package com.palantir.stash.codesearch.hook;

import com.atlassian.stash.hook.*;
import com.atlassian.stash.repository.*;
import java.util.Collection;
import com.palantir.stash.codesearch.updater.SearchUpdater;

public class PostReceiveUpdaterHook implements PostReceiveHook {

    private final SearchUpdater updater;

    public PostReceiveUpdaterHook (SearchUpdater updater) {
        this.updater = updater;
    }

    @Override
    public void onReceive (Repository repository, Collection<RefChange> refChanges,
            HookResponse resp) {
        for (RefChange change : refChanges) {
            if (change.getType() == RefChangeType.DELETE) {
                // TODO: ref deletion
            } else {
                updater.submitAsyncUpdate(repository, change.getRefId(), 0);
            }
        }
    }

}
