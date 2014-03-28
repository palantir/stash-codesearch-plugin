package com.palantir.stash.codesearch.updater;

import com.atlassian.stash.repository.*;
import com.atlassian.stash.scm.git.GitScm;
import com.google.common.collect.ImmutableMap;
import com.palantir.stash.codesearch.admin.GlobalSettings;
import com.palantir.stash.codesearch.admin.GlobalSettingsManager;
import com.palantir.stash.codesearch.repository.RepositoryServiceManager;
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
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequestBuilder;
import org.elasticsearch.cluster.metadata.AliasMetaData;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static com.palantir.stash.codesearch.elasticsearch.ElasticSearch.*;

public class SearchUpdaterImpl implements SearchUpdater {

    private static final int MAX_CONCURRENT_INDEX_JOBS = 4;

    private static final Logger log = LoggerFactory.getLogger(SearchUpdaterImpl.class);

    private final GitScm gitScm;

    private final GlobalSettingsManager globalSettingsManager;

    private final RepositoryServiceManager repositoryServiceManager;

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

    // Between a reindex and alias switching, we need to block all new index requests.
    private final AtomicBoolean jobPoolBlocked;

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
            GitScm gitScm,
            GlobalSettingsManager globalSettingsManager,
            RepositoryServiceManager repositoryServiceManager,
            SearchUpdateJobFactory jobFactory) {
        this.gitScm = gitScm;
        this.globalSettingsManager = globalSettingsManager;
        this.repositoryServiceManager = repositoryServiceManager;
        this.jobFactory = jobFactory;
        this.random = new SecureRandom();
        this.runningJobs = new HashSet<SearchUpdateJob>();
        this.isReindexingAll = new AtomicBoolean(false);
        this.jobPoolBlocked = new AtomicBoolean(false);
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
        if (!overwrite && prevIndex != null) {
            return false;
        }

        String newIndex = random.nextLong() + "-" + System.nanoTime();
        try {
            ES_CLIENT.admin().indices().prepareCreate(newIndex)
                // Latest indexed note schema
                .addMapping("latestindexed",
                    jsonBuilder().startObject()
                        .startObject("properties")
                            .startObject("project")
                                .field("type", "string")
                                .field("index", "not_analyzed")
                            .endObject()
                            .startObject("repository")
                                .field("type", "string")
                                .field("index", "not_analyzed")
                            .endObject()
                            .startObject("ref")
                                .field("type", "string")
                                .field("index", "not_analyzed")
                            .endObject()
                            .startObject("hash")
                                .field("type", "string")
                                .field("index", "not_analyzed")
                            .endObject()
                        .endObject()
                    .endObject())
                // Commit schema
                .addMapping("commit",
                    jsonBuilder().startObject()
                        .startObject("properties")
                            .startObject("project")
                                .field("type", "string")
                                .field("index", "not_analyzed")
                            .endObject()
                            .startObject("repository")
                                .field("type", "string")
                                .field("index", "not_analyzed")
                            .endObject()
                            .startObject("refs")
                                .field("type", "multi_field")
                                .startObject("fields")
                                    .startObject("refs")
                                        .field("type", "string")
                                        .field("index_analyzer", "ref_analyzer")
                                        .field("search_analyzer", "ref_analyzer")
                                    .endObject()
                                    .startObject("untouched")
                                        .field("type", "string")
                                        .field("index", "not_analyzed")
                                    .endObject()
                                .endObject()
                            .endObject()
                            .startObject("authorname")
                                .field("type", "string")
                                .field("index_analyzer", "name_analyzer")
                                .field("search_analyzer", "name_analyzer")
                            .endObject()
                            .startObject("authoremail")
                                .field("type", "multi_field")
                                .startObject("fields")
                                    .startObject("authoremail")
                                        .field("type", "string")
                                        .field("index_analyzer", "email_analyzer")
                                        .field("search_analyzer", "email_analyzer")
                                    .endObject()
                                    .startObject("untouched")
                                        .field("type", "string")
                                        .field("index", "not_analyzed")
                                    .endObject()
                                .endObject()
                            .endObject()
                            .startObject("body")
                                .field("type", "string")
                            .endObject()
                            .startObject("subject")
                                .field("type", "string")
                            .endObject()
                            .startObject("commitdate")
                                .field("type", "date")
                                .field("format", "date_time")
                            .endObject()
                            .startObject("hash")
                                .field("type", "string")
                                .field("index", "not_analyzed")
                            .endObject()
                        .endObject()
                    .endObject())
                // File schema
                .addMapping("file",
                    jsonBuilder().startObject()
                        .startObject("properties")
                            .startObject("project")
                                .field("type", "string")
                                .field("index", "not_analyzed")
                            .endObject()
                            .startObject("repository")
                                .field("type", "string")
                                .field("index", "not_analyzed")
                            .endObject()
                            .startObject("refs")
                                .field("type", "multi_field")
                                .startObject("fields")
                                    .startObject("refs")
                                        .field("type", "string")
                                        .field("index_analyzer", "ref_analyzer")
                                        .field("search_analyzer", "ref_analyzer")
                                    .endObject()
                                    .startObject("untouched")
                                        .field("type", "string")
                                        .field("index", "not_analyzed")
                                    .endObject()
                                .endObject()
                            .endObject()
                            .startObject("blob")
                                .field("type", "string")
                                .field("index", "not_analyzed")
                            .endObject()
                            .startObject("path")
                                .field("type", "string")
                                .field("index_analyzer", "path_analyzer")
                                .field("search_analyzer", "path_analyzer")
                            .endObject()
                            .startObject("extension")
                                .field("type", "string")
                                .field("index", "not_analyzed")
                            .endObject()
                            .startObject("contents")
                                .field("type", "string")
                                .field("index_analyzer", "code_analyzer")
                                .field("search_analyzer", "code_analyzer")
                            .endObject()
                            .startObject("charcount")
                                .field("type", "integer")
                                .startObject("fielddata")
                                    .field("format", "doc_values")
                                .endObject()
                            .endObject()
                            .startObject("linecount")
                                .field("type", "integer")
                                .startObject("fielddata")
                                    .field("format", "doc_values")
                                .endObject()
                            .endObject()
                        .endObject()
                    .endObject())
                .setSettings(
                    jsonBuilder().startObject()
                        .startObject("analysis")
                            .startObject("analyzer")
                                .startObject("email_analyzer")
                                    .field("type", "pattern")
                                .endObject()
                                .startObject("name_analyzer")
                                    .field("type", "pattern")
                                .endObject()
                                .startObject("ref_analyzer")
                                    .field("type", "pattern")
                                    .field("pattern", "/")
                                .endObject()
                                .startObject("code_analyzer")
                                    .field("type", "pattern")
                                .endObject()
                                .startObject("path_analyzer")
                                    .field("type", "pattern")
                                    .field("pattern", "[/\\\\.]")
                                .endObject()
                            .endObject()
                            .startObject("filter")
                            .endObject()
                        .endObject()
                    .endObject())
                .get();
        } catch (Exception e) {
            log.error("Caught exception while creating {} ({}), aborting", newIndex, alias, e);
            return false;
        }

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
                final GlobalSettings globalSettings = globalSettingsManager.getGlobalSettings();
                if (!globalSettings.getIndexingEnabled()) {
                    log.warn("Not executing SearchUpdateJob {} since indexing is disabled",
                        job.toString());
                    return;
                }
                try {
                    if (reindex) {
                        job.doReindex(gitScm, globalSettings);
                    } else {
                        job.doUpdate(gitScm, globalSettings);
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

    private Future submitAsyncUpdateImpl (Repository repository, String ref,
            int delayMs, boolean reindex) {
        if (jobPoolBlocked.get()) {
            log.warn("Job pool is currently blocked: not executing update job on {}/{}:{}",
                repository.getProject().getKey(), repository.getSlug(), ref);
            return getFinishedFuture();
        }
        SearchUpdateJob job = jobFactory.newDefaultJob(repository, ref);
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

    private void submitUpdateImpl (Repository repository, String ref, int delayMs,
            boolean reindex) {
        waitForFuture(submitAsyncUpdateImpl(repository, ref, delayMs, reindex));
    }

    @Override
    public Future submitAsyncUpdate (Repository repository, String ref, int delayMs) {
        return submitAsyncUpdateImpl(repository, ref, delayMs, false);
    }

    @Override
    public Future submitAsyncReindex (Repository repository, String ref, int delayMs) {
        return submitAsyncUpdateImpl(repository, ref, delayMs, true);
    }

    @Override
    public void submitUpdate (Repository repository, String ref, int delayMs) {
        submitUpdateImpl(repository, ref, delayMs, false);
    }

    @Override
    public void submitReindex (Repository repository, String ref, int delayMs) {
        submitUpdateImpl(repository, ref, delayMs, true);
    }

    @Override
    public boolean reindexAll () {
        GlobalSettings globalSettings = globalSettingsManager.getGlobalSettings();
        if (!globalSettings.getIndexingEnabled()) {
            log.warn("Not performing a complete reindex triggered since indexing is disabled");
            return false;
        }
        if (!isReindexingAll.compareAndSet(false, true)) {
            log.warn("Not performing a complete reindex since one is already occurring");
            return false;
        }
        try {
            // Wait for job pool to clear out
            jobPoolBlocked.set(true);
            try {
                int zeroJobIntervals = 0;
                while (zeroJobIntervals < 5) {
                    if (jobPool.getCompletedTaskCount() >= jobPool.getTaskCount()) {
                        ++zeroJobIntervals;
                    } else {
                        zeroJobIntervals = 0;
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        log.warn("Caught error while trying to sleep", e);
                    }
                }
                initializeAliasedIndex(ES_UPDATEALIAS, true);
            } finally {
                jobPoolBlocked.set(false);
            }

            // Disable refresh for faster bulk indexing
            ES_CLIENT.admin().indices().prepareUpdateSettings(ES_UPDATEALIAS)
                .setSettings(ImmutableSettings.builder()
                    .put("index.refresh_interval", "-1"))
                .get();

            // Submit and wait for each job
            List<Future> futures = new ArrayList<Future>();
            for (Repository repo : repositoryServiceManager.getRepositoryMap(null).values()) {
                for (Branch branch : repositoryServiceManager.getBranchMap(repo).values()) {
                    // No need to explicitly trigger reindex, since index is empty.
                    futures.add(submitAsyncUpdate(repo, branch.getId(), 0));
                }
            }
            for (Future future : futures) {
                waitForFuture(future);
            }

            // Re-enable refresh & optimize, enable searching on index
            ES_CLIENT.admin().indices().prepareUpdateSettings(ES_UPDATEALIAS)
                .setSettings(ImmutableSettings.builder()
                    .put("index.refresh_interval", "1s"))
                .get();
            ES_CLIENT.admin().indices().prepareOptimize(ES_UPDATEALIAS).get();
            redirectAndDeleteAliasedIndex(ES_SEARCHALIAS, ES_UPDATEALIAS);
        } finally {
            isReindexingAll.getAndSet(false);
            return true;
        }
    }

}
