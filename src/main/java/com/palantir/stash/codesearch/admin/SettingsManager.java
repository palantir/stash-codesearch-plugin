/**
 * Stores and retrieves codesearch global settings.
 */

package com.palantir.stash.codesearch.admin;
import com.atlassian.stash.repository.Repository;
import com.palantir.stash.codesearch.updater.SearchUpdater;

public interface SettingsManager {

    GlobalSettings getGlobalSettings ();

    GlobalSettings setGlobalSettings (
        boolean indexingEnabled,
        int maxConcurrentIndexing,
        int maxFileSize,
        int searchTimeout,
        String noHighlightExtensions,
        int maxPreviewLines,
        int maxMatchLines,
        int maxFragments,
        int pageSize,
        double commitHashBoost,
        double commitSubjectBoost,
        double commitBodyBoost,
        double fileNameBoost);

    RepositorySettings getRepositorySettings (Repository repository);

    RepositorySettings setRepositorySettings (
        Repository repository,
        String refRegex);

    void addSearchUpdater (SearchUpdater updater);

}
