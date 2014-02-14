package com.palantir.stash.codesearch.hook;

import com.atlassian.stash.hook.repository.*;
import com.atlassian.stash.repository.*;
import com.atlassian.stash.setting.*;
import java.net.URL;
import java.util.Collection;
import com.palantir.stash.codesearch.updater.SearchUpdater;

public class PostReceiveUpdaterHook
        implements AsyncPostReceiveRepositoryHook, RepositorySettingsValidator {

    private final SearchUpdater updater;

    public PostReceiveUpdaterHook (SearchUpdater updater) {
        this.updater = updater;
    }

    @Override
    public void postReceive (RepositoryHookContext context, Collection<RefChange> refChanges) {
         for (RefChange change : refChanges) {
             if (change.getType() != RefChangeType.DELETE) {
                 updater.submitAsyncUpdate(context.getRepository(), change.getRefId(), 0);
             }
         }
    }

    @Override
    public void validate (Settings settings, SettingsValidationErrors errors,
            Repository repository) {
        // No settings to validate
    }

}
