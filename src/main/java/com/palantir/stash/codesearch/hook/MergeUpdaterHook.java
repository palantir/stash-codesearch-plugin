package com.palantir.stash.codesearch.hook;

import com.atlassian.stash.hook.repository.*;
import com.atlassian.stash.pull.*;
import com.atlassian.stash.repository.*;
import com.atlassian.stash.setting.*;
import java.util.regex.Pattern;
import com.palantir.stash.codesearch.updater.SearchUpdater;

public class MergeUpdaterHook implements RepositoryMergeRequestCheck, RepositorySettingsValidator {

    /**
     * We should allocate some time for the merge to occur before indexing. Unfortunately, there's
     * no way AFAIK to make Stash execute this hook right after the merge occurs.
     */
    private static final int MERGE_UPDATE_DELAY = 20000; // milliseconds

    private final SearchUpdater updater;

    public MergeUpdaterHook (SearchUpdater updater) {
        this.updater = updater;
    }

    @Override
    public void check (RepositoryMergeRequestCheckContext context) {
        PullRequestRef pr = context.getMergeRequest().getPullRequest().getToRef();
        updater.submitAsyncUpdate(pr.getRepository(), pr.getId(), MERGE_UPDATE_DELAY);
    }

    @Override
    public void validate (Settings settings, SettingsValidationErrors errors,
            Repository repository) {
        // No settings to validate
    }

}
