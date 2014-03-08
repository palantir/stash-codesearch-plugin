/**
 * Interface for manipulating RepositoryService and RepositoryMetadataService.
 */

package com.palantir.stash.codesearch.manager;

import com.atlassian.stash.repository.*;
import com.atlassian.stash.user.PermissionValidationService;
import com.google.common.collect.ImmutableMap;

public interface RepositoryServiceManager {

    // Returns map of PROJECTKEY^REPOSLUG to repository object. If validationService is not null,
    // will validate against the service's authentication context for read permissions.
    ImmutableMap<String, Repository> getRepositoryMap (
        PermissionValidationService validationService);

    // Returns map of ref name to branch object
    ImmutableMap<String, Branch> getBranchMap (Repository repository);

}
