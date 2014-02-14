package com.palantir.stash.codesearch.updater;

import com.atlassian.stash.repository.Branch;
import com.atlassian.stash.repository.Repository;

public class SearchUpdateJobFactoryImpl implements SearchUpdateJobFactory {

    public SearchUpdateJob newDefaultJob (Repository repository, Branch branch) {
        return new SearchUpdateJobImpl(repository, branch);
    }

}
