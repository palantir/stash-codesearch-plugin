/**
 * Interface for constructing SearchUpdateJob objects.
 */

package com.palantir.stash.codesearch.updater;

import com.atlassian.stash.repository.Repository;

public interface SearchUpdateJobFactory {

    /**
     * Returns the default SearchUpdateJob implementation for the specified repo & ref.
     */
    SearchUpdateJob newDefaultJob (Repository repository, String ref);

}
