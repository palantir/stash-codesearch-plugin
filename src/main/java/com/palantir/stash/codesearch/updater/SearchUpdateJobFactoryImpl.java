package com.palantir.stash.codesearch.updater;

import com.atlassian.stash.repository.Repository;

public class SearchUpdateJobFactoryImpl implements SearchUpdateJobFactory {

    public SearchUpdateJob newDefaultJob (Repository repository, String ref) {
        return new SearchUpdateJobImpl(repository, ref);
    }

}
