/**
 * Default implementation of RepositoryServiceManager.
 */

package com.palantir.stash.codesearch.repository;

import com.atlassian.stash.exception.AuthorisationException;
import com.atlassian.stash.repository.Branch;
import com.atlassian.stash.repository.RefOrder;
import com.atlassian.stash.repository.Repository;
import com.atlassian.stash.repository.RepositoryBranchesRequest;
import com.atlassian.stash.repository.RepositoryMetadataService;
import com.atlassian.stash.repository.RepositoryService;
import com.atlassian.stash.user.Permission;
import com.atlassian.stash.user.PermissionValidationService;
import com.atlassian.stash.util.Page;
import com.atlassian.stash.util.PageRequest;
import com.atlassian.stash.util.PageRequestImpl;
import com.google.common.collect.ImmutableMap;

public class RepositoryServiceManagerImpl implements RepositoryServiceManager {

    private static final int PAGE_SIZE = 1000;

    private final RepositoryService repositoryService;

    private final RepositoryMetadataService repositoryMetadataService;

    public RepositoryServiceManagerImpl(
        RepositoryService repositoryService,
        RepositoryMetadataService repositoryMetadataService) {
        this.repositoryService = repositoryService;
        this.repositoryMetadataService = repositoryMetadataService;
    }

    @Override
    public ImmutableMap<String, Repository> getRepositoryMap(
        PermissionValidationService validationService) {
        PageRequest req = new PageRequestImpl(0, PAGE_SIZE);
        ImmutableMap.Builder<String, Repository> repoMap =
            new ImmutableMap.Builder<String, Repository>();
        while (true) {
            Page<? extends Repository> repoPage = repositoryService.findAll(req);
            for (Repository r : repoPage.getValues()) {
                try {
                    if (validationService != null) {
                        validationService.validateForRepository(r, Permission.REPO_READ);
                    }
                    repoMap.put(r.getProject().getKey() + "^" + r.getSlug(), r);
                } catch (AuthorisationException e) {
                    // User doesn't have permission to access the repo
                }
            }
            if (repoPage.getIsLastPage()) {
                break;
            }
            req = repoPage.getNextPageRequest();
        }
        return repoMap.build();
    }

    @Override
    public ImmutableMap<String, Branch> getBranchMap(Repository repository) {
        PageRequest req = new PageRequestImpl(0, PAGE_SIZE);
        ImmutableMap.Builder<String, Branch> branchMap = new ImmutableMap.Builder<String, Branch>();
        RepositoryBranchesRequest rbr =
            new RepositoryBranchesRequest.Builder().repository(repository).order(RefOrder.ALPHABETICAL).build();
        while (true) {
            Page<? extends Branch> branchPage = repositoryMetadataService.getBranches(rbr, req);

            for (Branch b : branchPage.getValues()) {
                branchMap.put(b.getId(), b);
            }
            if (branchPage.getIsLastPage()) {
                break;
            }
            req = branchPage.getNextPageRequest();
        }
        return branchMap.build();
    }

    @Override
    public RepositoryService getRepositoryService() {
        return repositoryService;
    }

    @Override
    public RepositoryMetadataService getRepositoryMetadataService() {
        return repositoryMetadataService;
    }

}
