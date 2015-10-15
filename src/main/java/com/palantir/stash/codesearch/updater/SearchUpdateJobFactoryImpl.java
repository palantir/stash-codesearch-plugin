package com.palantir.stash.codesearch.updater;

import com.atlassian.bitbucket.repository.Repository;
import com.palantir.stash.codesearch.logger.PluginLoggerFactory;
import com.palantir.stash.codesearch.search.SearchFilterUtils;

public class SearchUpdateJobFactoryImpl implements SearchUpdateJobFactory {

    public SearchUpdateJob newDefaultJob(SearchFilterUtils sfu, PluginLoggerFactory plf, Repository repository,
        String ref) {
        return new SearchUpdateJobImpl(sfu, plf, repository, ref);
    }

}
