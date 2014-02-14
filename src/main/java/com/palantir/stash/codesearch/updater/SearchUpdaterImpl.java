package com.palantir.stash.codesearch.updater;

import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequestBuilder;
import org.elasticsearch.cluster.metadata.AliasMetaData;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.atlassian.stash.repository.*;
import com.atlassian.stash.user.*;
import com.atlassian.stash.util.*;
import com.atlassian.stash.scm.git.GitScm;
import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.security.SecureRandom;

import static com.palantir.stash.codesearch.elasticsearch.ElasticSearch.*;


import java.util.Arrays;
public class SearchUpdaterImpl implements SearchUpdater {

    // TODO: make this configurable (figure out atlassian plugin settings)
    private static final int MAX_CONCURRENT_INDEX_JOBS = 4;

    private static final Logger log = LoggerFactory.getLogger(SearchUpdaterImpl.class);

    private final RepositoryService repositoryService;

    private final RepositoryMetadataService metadataService;

    private final GitScm gitScm;

    private final SearchUpdateJobFactory jobFactory;

    private final SecureRandom random;

    // Lock pool for update jobs (see acquireLock() and releaseLock())
    private final Set<SearchUpdateJob> runningJobs;

    /**
     * Since full reindexes are costly, we definitely don't want to do more than one at a time.
     * We use AtomicBoolean instead of a lock so that we can immediately inform the user if a
     * reindex is already occurring.
     */
    private final AtomicBoolean isReindexingAll;

    /**
     * Why do we use both a semaphore and a thread pool to control execution? Since each indexing
     * job must be locked on its corresponding branch, there could be a bunch of jobs "executing"
     * in the thread pool that are simply blocking on the lock acquisition. Therefore, we use
     * the semaphore to control the jobs which are actually running, and we use the thread pool
     * (of a larger capacity) to stage jobs that are waiting on the lock.
     */
    private final Semaphore semaphore;

    private final ScheduledThreadPoolExecutor jobPool;

    public SearchUpdaterImpl (
            RepositoryService repositoryService,
            RepositoryMetadataService metadataService,
            GitScm gitScm,
            SearchUpdateJobFactory jobFactory) {
        this.repositoryService = repositoryService;
        this.metadataService = metadataService;
        this.gitScm = gitScm;
        this.jobFactory = jobFactory;
        this.random = new SecureRandom();
        this.runningJobs = new HashSet<SearchUpdateJob>();
        this.isReindexingAll = new AtomicBoolean(false);
        this.semaphore = new Semaphore(MAX_CONCURRENT_INDEX_JOBS, true);
        this.jobPool = new ScheduledThreadPoolExecutor(MAX_CONCURRENT_INDEX_JOBS * 4);
        initializeAliasedIndex(ES_UPDATEALIAS, false);
        redirectAndDeleteAliasedIndex(ES_SEARCHALIAS, ES_UPDATEALIAS);
    }

    // Return the name of the index pointed to by an alias (null if no index found)
    private String getIndexFromAlias (String alias) {
        ImmutableOpenMap<String, List<AliasMetaData>> aliasMap =
            ES_CLIENT.admin().indices().prepareGetAliases(alias).get().getAliases();
        for (String index : aliasMap.keys().toArray(String.class)) {
            for (AliasMetaData aliasEntry : aliasMap.get(index)) {
                if (aliasEntry.getAlias().equals(alias)) {
                    return index;
                }
            }
        }
        return null;
    }

    /**
     * Initializes a stash-codesearch index with an alias pointing to it. If overwrite is true,
     * a new index will be created even if the alias is already assigned. Returns true iff a new
     * index was created.
     */
    private synchronized boolean initializeAliasedIndex (String alias, boolean overwrite) {
        String prevIndex = getIndexFromAlias(alias);
        if (overwrite && prevIndex != null) {
            return false;
        }

        // Generate new index -- TODO: mappings & analyzers
        String newIndex = random.nextLong() + "-" + System.nanoTime();
        ES_CLIENT.admin().indices().prepareCreate(newIndex)
            .get();

        // Perform alias switch
        IndicesAliasesRequestBuilder aliasBuilder = ES_CLIENT.admin().indices().prepareAliases();
        if (prevIndex != null) {
            aliasBuilder.removeAlias(prevIndex, alias);
        }
        aliasBuilder.addAlias(newIndex, alias).get();
        return true;
    }

    /**
     * Makes one alias point to another's index, deleting the old index afterwards. Returns true
     * iff the operation was successful.
     */
    private synchronized boolean redirectAndDeleteAliasedIndex (String fromAlias, String toAlias) {
        // Find indices corresponding to aliases
        String fromIndex = getIndexFromAlias(fromAlias);
        String toIndex = getIndexFromAlias(toAlias);

        if (toIndex == null) {
            log.error("{} does not resolve to an index", toAlias);
            return false;
        }

        if (toIndex.equals(fromIndex)) {
            log.warn("{} and {} resolve to the same index", fromAlias, toAlias);
            return false;
        }

        // Perform alias switch
        IndicesAliasesRequestBuilder builder = ES_CLIENT.admin().indices().prepareAliases();
        if (fromIndex != null) {
            builder.removeAlias(fromIndex, fromAlias);
        }
        builder.addAlias(toIndex, fromAlias).get();

        // Delete old index
        if (fromIndex != null) {
            ES_CLIENT.admin().indices().prepareDelete(fromIndex).get();
        }

        return true;
    }

    // Acquire a lock on an update job
    private void acquireLock (SearchUpdateJob job) {
        synchronized (runningJobs) {
            while (runningJobs.contains(job)) {
                try {
                    runningJobs.wait();
                } catch (InterruptedException e) {
                    // Do nothing -- still waiting for lock acquisition
                }
            }
            runningJobs.add(job);
        }
    }

    // Release a lock on an update job
    private void releaseLock (SearchUpdateJob job) {
        synchronized (runningJobs) {
            runningJobs.remove(job);
            runningJobs.notifyAll();
        }
    }

    /**
     * Returns a new runnable that executes a search updater job.
     */
    private Runnable getJobRunnable (final SearchUpdateJob job, final boolean reindex) {
        return new Runnable () {
            @Override
            public void run () {
                acquireLock(job);
                semaphore.acquireUninterruptibly();
                try {
                    if (reindex) {
                        job.doReindex(gitScm);
                    } else {
                        job.doUpdate(gitScm);
                    }
                } catch (Throwable e) {
                    log.error("Unexpected error while updating index for {}", job.toString(), e);
                } finally {
                    releaseLock(job);
                    semaphore.release();
                }
            }
        };
    }

    // Returns map of PROJECTKEY^REPOSLUG to repository
    private ImmutableMap<String, Repository> getRepositoryMap () {
        PageRequest req = new PageRequestImpl(0, 25);
        ImmutableMap.Builder<String, Repository> repoMap =
            new ImmutableMap.Builder<String, Repository>();
        while (true) {
            Page<? extends Repository> repoPage = repositoryService.findAll(req);
            for (Repository r : repoPage.getValues()) {
                repoMap.put(r.getProject().getKey() + "^" + r.getSlug(), r);
            }
            if (repoPage.getIsLastPage()) {
                break;
            }
            req = repoPage.getNextPageRequest();
        }
        return repoMap.build();
    }

    // Returns map of BRANCH_REF to branch
    private ImmutableMap<String, Branch> getBranchMap (Repository repository) {
        PageRequest req = new PageRequestImpl(0, 25);
        ImmutableMap.Builder<String, Branch> branchMap = new ImmutableMap.Builder<String, Branch>();
        while (true) {
            Page<? extends Branch> branchPage = metadataService.getBranches(
                repository, req, null, RefOrder.ALPHABETICAL);
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

    // Returns a dummy finished Future object
    private Future getFinishedFuture () {
        return new Future() {
            @Override public boolean cancel (boolean whatever) { return false; }
            @Override public Void get () { return null; }
            @Override public Void get (long timeout, TimeUnit unit) { return null; }
            @Override public boolean isCancelled () { return false; }
            @Override public boolean isDone () { return false; }
        };
    }

    private Future submitAsyncUpdateImpl (Repository repository, String branchRef,
            int delayMs, boolean reindex) {
        Branch branch = getBranchMap(repository).get(branchRef);
        if (branch == null) {
            log.error("ref {} does not exist, aborting", branchRef);
            return getFinishedFuture();
        }
        SearchUpdateJob job = jobFactory.newDefaultJob(repository, branch);
        Runnable jobRunnable = getJobRunnable(job, reindex);
        return jobPool.schedule(jobRunnable, delayMs, TimeUnit.MILLISECONDS);
    }

    // Waits uninterruptibly for a future to be satisfied
    private void waitForFuture (Future future) {
        boolean done = false;
        while (!done) {
            try {
                // Wait for job to terminate, then return.
                future.get();
                done = true;
            } catch (InterruptedException e) {
                // If we get an InterruptedException, continue waiting for the job to terminate.
            } catch (Exception e) {
                // All other exceptions should mark the end of the update job.
                done = true;
            }
        }
    }

    private void submitUpdateImpl (Repository repository, String branchRef, int delayMs,
            boolean reindex) {
        waitForFuture(submitAsyncUpdateImpl(repository, branchRef, delayMs, reindex));
    }

    @Override
    public Future submitAsyncUpdate (Repository repository, String branchRef, int delayMs) {
        return submitAsyncUpdateImpl(repository, branchRef, delayMs, false);
    }

    @Override
    public Future submitAsyncReindex (Repository repository, String branchRef, int delayMs) {
        return submitAsyncUpdateImpl(repository, branchRef, delayMs, true);
    }

    @Override
    public Future<Boolean> reindexAllAsync (int delayMs) {
        return jobPool.schedule(new Callable<Boolean>() {
            @Override
            public Boolean call () {
                return reindexAll();
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void submitUpdate (Repository repository, String branchRef, int delayMs) {
        submitUpdateImpl(repository, branchRef, delayMs, false);
    }

    @Override
    public void submitReindex (Repository repository, String branchRef, int delayMs) {
        submitUpdateImpl(repository, branchRef, delayMs, true);
    }

    @Override
    public boolean reindexAll () {
        if (!isReindexingAll.compareAndSet(false, true)) {
            return false;
        }
        try {
            initializeAliasedIndex(ES_UPDATEALIAS, true);
            List<Future> futures = new ArrayList<Future>();
            for (Repository repo : getRepositoryMap().values()) {
                for (Branch branch : getBranchMap(repo).values()) {
                    futures.add(submitAsyncReindex(repo, branch.getId(), 0));
                }
            }
            for (Future future : futures) {
                waitForFuture(future);
            }
            redirectAndDeleteAliasedIndex(ES_SEARCHALIAS, ES_UPDATEALIAS);
        } finally {
            isReindexingAll.getAndSet(false);
            return true;
        }
    }

}
