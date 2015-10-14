/**
 * Default implementation of RepositoryServiceManager.
 */

package com.palantir.stash.codesearch.repository;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;

import com.atlassian.bitbucket.AuthorisationException;
import com.atlassian.bitbucket.repository.Branch;
import com.atlassian.bitbucket.repository.RefOrder;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.repository.RepositoryBranchesRequest;
import com.atlassian.bitbucket.repository.RefService;
import com.atlassian.bitbucket.repository.RepositoryService;
import com.atlassian.bitbucket.permission.Permission;
import com.atlassian.bitbucket.permission.PermissionValidationService;
import com.atlassian.bitbucket.util.Page;
import com.atlassian.bitbucket.util.PageRequest;
import com.atlassian.bitbucket.util.PageRequestImpl;
import com.google.common.collect.ImmutableMap;
import com.palantir.stash.codesearch.logger.PluginLoggerFactory;

public class RepositoryServiceManagerImpl implements RepositoryServiceManager {

    private static final int PAGE_SIZE = 1000;

    private final RepositoryService repositoryService;

    private final RefService refService;

    private final Logger log;

    public RepositoryServiceManagerImpl(
        PluginLoggerFactory plf,
        RepositoryService repositoryService,
        RefService refService) {
        this.repositoryService = repositoryService;
        this.refService = refService;
        this.log = plf.getLogger(this.getClass().toString());
    }

    @Override
    public ImmutableMap<String, Repository> getRepositoryMap(
        PermissionValidationService validationService) {
        PageRequest req = new PageRequestImpl(0, PAGE_SIZE);
        Map<String, Repository> repoMap = new HashMap<String, Repository>();
        while (true) {
            Page<? extends Repository> repoPage = repositoryService.findAll(req);
            for (Repository r : repoPage.getValues()) {
                try {
                    if (validationService != null) {
                        validationService.validateForRepository(r, Permission.REPO_READ);
                    }
                    final String key = r.getProject().getKey() + "^" + r.getSlug();
                    if (repoMap.containsKey(key)) {
                        // ITOOLS-13350
                        log.error("Trying to insert existing key '" + key + "' intp repoMap with value " + r.toString());
                        continue;
                    }
                    repoMap.put(key, r);
                } catch (AuthorisationException e) {
                    // User doesn't have permission to access the repo
                }
            }
            if (repoPage.getIsLastPage()) {
                break;
            }
            req = repoPage.getNextPageRequest();
        }
        return ImmutableMap.copyOf(repoMap);
    }

    @Override
    public ImmutableMap<String, Branch> getBranchMap(Repository repository) {
        PageRequest req = new PageRequestImpl(0, PAGE_SIZE);
        Map<String, Branch> branchMap = new HashMap<String, Branch>();
        RepositoryBranchesRequest rbr =
            new RepositoryBranchesRequest.Builder(repository).order(RefOrder.ALPHABETICAL).build();
        while (true) {
            Page<? extends Branch> branchPage = refService.getBranches(rbr, req);

            for (Branch b : branchPage.getValues()) {
                if (branchMap.containsKey(b)) {
                    log.error("Tryting to insert existing key '" + b.getId() + "' into branchMap with value '" + b
                        + "'");
                    continue;
                }
                branchMap.put(b.getId(), b);
            }
            if (branchPage.getIsLastPage()) {
                break;
            }
            req = branchPage.getNextPageRequest();
        }
        return ImmutableMap.copyOf(branchMap);
    }

    @Override
    public RepositoryService getRepositoryService() {
        return repositoryService;
    }

    @Override
    public RefService getRefService() {
        return refService;
    }

}
