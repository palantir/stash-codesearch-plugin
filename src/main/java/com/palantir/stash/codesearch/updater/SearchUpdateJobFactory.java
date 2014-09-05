/**
 * Interface for constructing SearchUpdateJob objects.
 */

package com.palantir.stash.codesearch.updater;

import com.atlassian.stash.repository.Repository;
import com.palantir.stash.codesearch.logger.PluginLoggerFactory;
import com.palantir.stash.codesearch.search.SearchFilterUtils;

public interface SearchUpdateJobFactory {

    /**
     * Returns the default SearchUpdateJob implementation for the specified repo & ref.
     */
    SearchUpdateJob newDefaultJob(SearchFilterUtils sfu, PluginLoggerFactory plf, Repository repository, String ref);

}
