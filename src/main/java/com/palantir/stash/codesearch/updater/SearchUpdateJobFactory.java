/**
 * Interface for constructing SearchUpdateJob objects.
 */

package com.palantir.stash.codesearch.updater;

import com.atlassian.stash.repository.Branch;
import com.atlassian.stash.repository.Repository;

public interface SearchUpdateJobFactory {

    /**
     * Returns the default SearchUpdateJob implementation for the specified repo & branch.
     */
    SearchUpdateJob newDefaultJob (Repository repository, Branch branch);

}
