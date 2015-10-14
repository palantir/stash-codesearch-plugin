/**
 * Default implementation of SettingsManager.
 */

package com.palantir.stash.codesearch.admin;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.bitbucket.repository.Repository;
import com.palantir.stash.codesearch.updater.SearchUpdater;
import java.util.ArrayList;
import java.util.List;
import net.java.ao.DBParam;
import net.java.ao.Query;

public class SettingsManagerImpl implements SettingsManager {

    private static final String ID = "scs_global_settings";

    private final ActiveObjects ao;

    private final List<SearchUpdater> searchUpdaters;

    public SettingsManagerImpl (ActiveObjects ao) {
        this.ao = ao;
        this.searchUpdaters = new ArrayList<SearchUpdater>();
    }

    @Override
    public GlobalSettings getGlobalSettings () {
        GlobalSettings[] settings;
        synchronized (ao) {
            settings =
                ao.find(GlobalSettings.class, Query.select().where("GLOBAL_SETTINGS_ID = ?", ID));
        }
        if (settings.length > 0) {
            return settings[0];
        }
        synchronized (ao) {
            return ao.create(GlobalSettings.class, new DBParam("GLOBAL_SETTINGS_ID", ID));
        }
    }

    @Override
    public GlobalSettings setGlobalSettings (
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
            double fileNameBoost) {
        GlobalSettings[] settings;
        synchronized (ao) {
            settings =
                ao.find(GlobalSettings.class, Query.select().where("GLOBAL_SETTINGS_ID = ?", ID));
        }
        if (settings.length > 0) {
            settings[0].setIndexingEnabled(indexingEnabled);
            settings[0].setMaxConcurrentIndexing(maxConcurrentIndexing);
            settings[0].setMaxFileSize(maxFileSize);
            settings[0].setSearchTimeout(searchTimeout);
            settings[0].setNoHighlightExtensions(noHighlightExtensions);
            settings[0].setMaxPreviewLines(maxPreviewLines);
            settings[0].setMaxMatchLines(maxMatchLines);
            settings[0].setMaxFragments(maxFragments);
            settings[0].setPageSize(pageSize);
            settings[0].setCommitHashBoost(commitHashBoost);
            settings[0].setCommitSubjectBoost(commitSubjectBoost);
            settings[0].setCommitBodyBoost(commitBodyBoost);
            settings[0].setFileNameBoost(fileNameBoost);
            settings[0].save();
            for (SearchUpdater updater : searchUpdaters) {
                updater.refreshConcurrencyLimit();
            }
            return settings[0];
        }
        synchronized (ao) {
            return ao.create(GlobalSettings.class, new DBParam("GLOBAL_SETTINGS_ID", ID),
                new DBParam("INDEXING_ENABLED", indexingEnabled),
                new DBParam("MAX_CONCURRENT_INDEXING", maxConcurrentIndexing),
                new DBParam("MAX_FILE_SIZE", maxFileSize),
                new DBParam("SEARCH_TIMEOUT", searchTimeout),
                new DBParam("NO_HIGHLIGHT_EXTENSIONS", noHighlightExtensions),
                new DBParam("MAX_PREVIEW_LINES", maxPreviewLines),
                new DBParam("MAX_MATCH_LINES", maxMatchLines),
                new DBParam("MAX_FRAGMENTS", maxFragments),
                new DBParam("PAGE_SIZE", pageSize),
                new DBParam("COMMIT_HASH_BOOST", commitHashBoost),
                new DBParam("COMMIT_SUBJECT_BOOST", commitSubjectBoost),
                new DBParam("COMMIT_BODY_BOOST", commitBodyBoost),
                new DBParam("FILE_NAME_BOOST", fileNameBoost)
            );
        }
    }

    @Override
    public RepositorySettings getRepositorySettings (Repository repository) {
        String repoId = repository.getProject().getKey() + "^" + repository.getSlug();
        RepositorySettings [] settings;
        synchronized (ao) {
            settings = ao.find(RepositorySettings.class,
                Query.select().where("REPOSITORY_ID = ?", repoId));
        }
        if (settings.length > 0) {
            return settings[0];
        }
        synchronized (ao) {
            return ao.create(RepositorySettings.class, new DBParam("REPOSITORY_ID", repoId));
        }
    }

    @Override
    public RepositorySettings setRepositorySettings (
            Repository repository,
            String refRegex) {
        String repoId = repository.getProject().getKey() + "^" + repository.getSlug();
        RepositorySettings[] settings;
        synchronized (ao) {
            settings = ao.find(RepositorySettings.class,
                Query.select().where("REPOSITORY_ID = ?", repoId));
        }
        if (settings.length > 0) {
            settings[0].setRefRegex(refRegex);
            settings[0].save();
            return settings[0];
        }
        synchronized (ao) {
            return ao.create(RepositorySettings.class, new DBParam("REPOSITORY_ID", repoId),
                new DBParam("REF_REGEX", refRegex)
            );
        }
    }


    @Override
    public void addSearchUpdater (SearchUpdater updater) {
        searchUpdaters.add(updater);
    }

}
