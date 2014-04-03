/**
 * Default implementation of SearchUpdateJobImpl that incrementally indexes a ref's source code
 * and commits.
 */

package com.palantir.stash.codesearch.updater;

import com.atlassian.stash.repository.*;
import com.atlassian.stash.scm.git.*;
import com.google.common.collect.ImmutableList;
import com.palantir.stash.codesearch.admin.GlobalSettings;
import com.palantir.stash.codesearch.elasticsearch.RequestBuffer;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.ArrayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.elasticsearch.action.bulk.*;
import org.elasticsearch.action.search.*;
import org.elasticsearch.action.update.UpdateRequestBuilder;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.search.SearchHit;

import static com.palantir.stash.codesearch.elasticsearch.ElasticSearch.*;
import static com.palantir.stash.codesearch.search.SearchFilters.*;
import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.index.query.FilterBuilders.*;
import static org.elasticsearch.index.query.QueryBuilders.*;

class SearchUpdateJobImpl implements SearchUpdateJob {

    private static final Logger log = LoggerFactory.getLogger(SearchUpdateJobImpl.class);

    private static final String EMPTY_TREE = "4b825dc642cb6eb9a060e54bf8d69288fbee4904";

    private static final String NULL_HASH = "0000000000000000000000000000000000000000";

    private static final int MAX_ES_RETRIES = 10;

    private final Repository repository;

    private final String ref;

    public SearchUpdateJobImpl (Repository repository, String ref) {
        this.repository = repository;
        this.ref = ref;
    }

    @Override
    public boolean equals (Object o) {
        if (!(o instanceof SearchUpdateJobImpl) || o == null) {
            return false;
        }
        SearchUpdateJobImpl other = (SearchUpdateJobImpl) o;
        return repository.getSlug().equals(other.repository.getSlug()) &&
            repository.getProject().getKey().equals(other.repository.getProject().getKey()) &&
            ref.equals(other.ref);
    }

    @Override
    public int hashCode () {
        return toString().hashCode();
    }

    private String getRepoDesc () {
        return repository.getProject().getKey() + "^" + repository.getSlug();
    }

    @Override
    public String toString () {
        return getRepoDesc() + "^" + ref;
    }

    @Override
    public Repository getRepository () {
        return repository;
    }

    @Override
    public String getRef () {
        return ref;
    }

    /**
     * For incremental updates, we store the hashes of each ref's latest indexed commit in
     * ES_UPDATEALIAS. The following three methods provide note reading, adding, and deleting
     * functionality.
     */

    // Returns EMPTY_TREE if no commits were indexed before this.
    private String getLatestIndexedHash () {
        try {
            String hash = ES_CLIENT.prepareGet(ES_UPDATEALIAS, "latestindexed", toString())
                .get().getSourceAsMap().get("hash").toString();
            if (hash != null && hash.length() == 40) {
                return hash;
            }
        } catch (Exception e) {
            log.warn("Caught error getting the latest indexed commit for {}, returning EMPTY_TREE",
                toString(), e);
        }
        return EMPTY_TREE;
    }

    // Returns true iff successful
    private boolean deleteLatestIndexedNote () {
        try {
            ES_CLIENT.prepareDelete(ES_UPDATEALIAS, "latestindexed", toString()).get();
        } catch (Exception e) {
            log.error("Caught error deleting the latest indexed commit note for {} from the index",
                toString(), e);
            return false;
        }
        return true;
    }

    // Returns true iff successful
    private boolean addLatestIndexedNote (String commitHash) {
        try {
            ES_CLIENT.prepareIndex(ES_UPDATEALIAS, "latestindexed", toString())
                .setSource(jsonBuilder()
                .startObject()
                    .field("project", repository.getProject().getKey())
                    .field("repository", repository.getSlug())
                    .field("ref", ref)
                    .field("hash", commitHash)
                .endObject())
                .get();
        } catch (Exception e) {
            log.error("Caught error adding the latest indexed hash {}:{} to the index",
                toString(), commitHash, e);
            return false;
        }
        return true;
    }

    // Returns the hash of the latest commit on the job's ref (null if not found)
    private String getLatestHash (GitCommandBuilderFactory builderFactory) {
        try {
            String newHash = builderFactory.builder(repository)
                .command("show-ref")
                .argument("--verify")
                .argument(ref)
                .build(new StringOutputHandler()).call()
                .split("\\s+")[0];
            if (newHash.length() != 40) {
                log.error("Commit hash {} has invalid length", newHash);
                return null;
            }
            return newHash;
        } catch (Exception e) {
            log.error("Caught error while trying to resolve {}", ref, e);
            return null;
        }
    }

    // Returns a request to delete the ref from an arbitrary document with a refs field
    private UpdateRequestBuilder buildDeleteFromRef (String type, String id) {
        return ES_CLIENT.prepareUpdate(ES_UPDATEALIAS, type, id)
            .setScript("ctx._source.refs.contains(ref) ? ((ctx._source.refs.size() > 1) " +
                       "? (ctx._source.refs.remove(ref)) : (ctx.op = \"delete\")) : (ctx.op = \"none\")")
            .setScriptLang("mvel")
            .addScriptParam("ref", ref)
            .setRetryOnConflict(MAX_ES_RETRIES)
            .setRouting(getRepoDesc());
    }

    // Returns a request to add the ref to an arbitrary document with a refs field
    private UpdateRequestBuilder buildAddToRef (String type, String id) {
        return ES_CLIENT.prepareUpdate(ES_UPDATEALIAS, type, id)
            .setScript("ctx._source.refs.contains(ref) " +
                       "? (ctx.op = \"none\") : (ctx._source.refs += ref)")
            .setScriptLang("mvel")
            .addScriptParam("ref", ref)
            .setRetryOnConflict(MAX_ES_RETRIES)
            .setRouting(getRepoDesc());
    }

    // Returns a request to delete a blob/path pair from the index.
    private UpdateRequestBuilder buildDeleteFileFromRef (String blob, String path) {
        String fileId = getRepoDesc() + "^" + blob + "^:/" + path;
        return buildDeleteFromRef("file", fileId);
    }

    // Returns a request to add a file to a ref via an update script. Will fail if document is not
    // in the index.
    private UpdateRequestBuilder buildAddFileToRef (String blob, String path) {
        String fileId = getRepoDesc() + "^" + blob + "^:/" + path;
        return buildAddToRef("file", fileId);
   }

    // Returns a request to delete a commit from the index.
    private UpdateRequestBuilder buildDeleteCommitFromRef (String commitHash) {
        String commitId = getRepoDesc() + "^" + commitHash;
        return buildDeleteFromRef("commit", commitId);
    }

    // Returns a request to add a commit to a ref via an update script. Will fail if document is not
    // in the index.
    private UpdateRequestBuilder buildAddCommitToRef (String commitHash) {
        String commitId = getRepoDesc() + "^" + commitHash;
        return buildAddToRef("commit", commitId);
    }

    @Override
    public void doReindex (GitScm gitScm, GlobalSettings globalSettings) {
        if (!globalSettings.getIndexingEnabled()) {
            return;
        }
        deleteLatestIndexedNote();
        while (true) {
            try {
                SearchRequestBuilder esReq = ES_CLIENT.prepareSearch(ES_UPDATEALIAS)
                    .setSize(1000)
                    .setFetchSource(false)
                    .setRouting(getRepoDesc())
                    .setQuery(filteredQuery(matchAllQuery(), andFilter(
                        projectRepositoryFilter(
                            repository.getProject().getKey(), repository.getSlug()),
                        exactRefFilter(ref))));
                BulkRequestBuilder bulkDelete = ES_CLIENT.prepareBulk().setRefresh(true);
                for (SearchHit hit : esReq.get().getHits().getHits()) {
                    bulkDelete.add(buildDeleteFromRef(hit.getType(), hit.getId()));
                }
                if (bulkDelete.numberOfActions() == 0) {
                    break;
                }
                bulkDelete.get();
            } catch (Exception e) {
                log.error("Could not delete documents for {}, aborting", toString(), e);
                return;
            }
        }
        doUpdate(gitScm, globalSettings);
    }

    @Override
    public void doUpdate (GitScm gitScm, GlobalSettings globalSettings) {
        if (!globalSettings.getIndexingEnabled()) {
            return;
        }

        GitCommandBuilderFactory builderFactory = gitScm.getCommandBuilderFactory();

        // List of bulk requests to execute sequentially at the end of the method
        RequestBuffer requestBuffer = new RequestBuffer(ES_CLIENT);

        // Unique identifier for repo
        String repoDesc = getRepoDesc();

        // Unique identifier for ref 
        String refDesc = toString();

        // Hash of latest indexed commit
        String prevHash = getLatestIndexedHash();

        // Hash of latest commit on ref
        String newHash = getLatestHash(builderFactory);
        if (newHash == null) {
            log.error("Aborting since hash is invalid");
            return;
        }

        // Diff for files & process changes
        Set<SimpleEntry<String, String>> filesToAdd =
            new LinkedHashSet<SimpleEntry<String, String>>();
        try {
            // Get diff --raw -z tokens
            String[] diffToks = builderFactory.builder(repository)
                .command("diff")
                .argument("--raw").argument("--abbrev=40").argument("-z")
                .argument(prevHash).argument(newHash)
                .build(new StringOutputHandler()).call()
                .split("\u0000");

            // Process each diff --raw -z entry
            for (int curTok = 0; curTok < diffToks.length; ++curTok) {
                String[] statusToks = diffToks[curTok].split(" ");
                if (statusToks.length < 5) {
                    break;
                }
                String status = statusToks[4];
                String oldBlob = statusToks[2];
                String newBlob = statusToks[3];

                // File added
                if (status.startsWith("A")) {
                    String path = diffToks[++curTok];
                    filesToAdd.add(new SimpleEntry(newBlob, path));

                // File copied
                } else if (status.startsWith("C")) {
                    String toPath = diffToks[curTok += 2];
                    filesToAdd.add(new SimpleEntry(newBlob, toPath));

                // File deleted
                } else if (status.startsWith("D")) {
                    String path = diffToks[++curTok];
                    requestBuffer.add(buildDeleteFileFromRef(oldBlob, path));

                // File modified
                } else if (status.startsWith("M") || status.startsWith("T")) {
                    String path = diffToks[++curTok];
                    if (!oldBlob.equals(newBlob)) {
                        requestBuffer.add(buildDeleteFileFromRef(oldBlob, path));
                        filesToAdd.add(new SimpleEntry(newBlob, path));
                    }

                // File renamed
                } else if (status.startsWith("R")) {
                    String fromPath = diffToks[++curTok];
                    String toPath = diffToks[++curTok];
                    requestBuffer.add(buildDeleteFileFromRef(oldBlob, fromPath));
                    filesToAdd.add(new SimpleEntry(newBlob, toPath));

                // Unknown change
                } else if (status.startsWith("X")) {
                    throw new RuntimeException("Status letter 'X' is a git bug.");
                }
            }
        } catch (Exception e) {
            log.error("Caught error while diffing between {} and {}, aborting update",
                prevHash, newHash, e);
            return;
        }
        log.debug("{} update: adding {} files", refDesc, filesToAdd.size());

        // Add new blob/path pairs. We use another bulk request here to cut down on the number of
        // cat-files we need to perform -- if a blob already exists in the ES cluster, we can
        // simply add the ref to the refs array.
        if (!filesToAdd.isEmpty()) {
            try {
                BulkRequestBuilder bulkFileRefUpdate = ES_CLIENT.prepareBulk();
                ImmutableList<SimpleEntry<String, String>> filesToAddCopy =
                    ImmutableList.copyOf(filesToAdd);
                for (SimpleEntry<String, String> bppair : filesToAddCopy) {
                    String blob = bppair.getKey(), path = bppair.getValue();
                    bulkFileRefUpdate.add(buildAddFileToRef(blob, path));
                }
                BulkItemResponse[] responses = bulkFileRefUpdate.get().getItems();
                if (responses.length != filesToAddCopy.size()) {
                    throw new IndexOutOfBoundsException(
                        "Bulk resp. array must have the same length as original request array");
                }

                // Process all update responses
                int count = 0;
                for (SimpleEntry<String, String> bppair : filesToAddCopy) {
                    if (!responses[count].isFailed()) {
                        // Update was successful, no need to index file
                        filesToAdd.remove(bppair);
                    }
                    ++count;
                }
            } catch (Exception e) {
                log.warn("file-ref update failed, performing upserts for all changes", e);
            }
        }
        log.debug("{} update: {} files to upsert", refDesc, filesToAdd.size());

        // Process all changes w/o corresponding documents
        if (!filesToAdd.isEmpty()) {
            try {
                // Get filesizes and prune all files that exceed the filesize limit
                ImmutableList<SimpleEntry<String, String>> filesToAddCopy =
                    ImmutableList.copyOf(filesToAdd);
                CatFileInputHandler catFileInput = new CatFileInputHandler();
                for (SimpleEntry<String, String> bppair : filesToAddCopy) {
                    catFileInput.addObject(bppair.getKey());
                }
                String[] catFileMetadata = builderFactory.builder(repository)
                    .command("cat-file")
                    .argument("--batch-check")
                    .inputHandler(catFileInput)
                    .build(new StringOutputHandler()).call()
                    .split("\n");
                if (filesToAdd.size() != catFileMetadata.length) {
                    throw new IndexOutOfBoundsException(
                        "git cat-file --batch-check returned wrong number of lines");
                }
                CatFileOutputHandler catFileOutput = new CatFileOutputHandler();
                int count = 0;
                int maxFileSize = globalSettings.getMaxFileSize();
                for (SimpleEntry<String, String> bppair : filesToAddCopy) {
                    int fs;
                    try {
                        fs = Integer.parseInt(catFileMetadata[count].split("\\s")[2]);
                    } catch (Exception e) {
                        fs = Integer.MAX_VALUE;
                    }

                    if (fs > maxFileSize) {
                        filesToAdd.remove(bppair);
                    } else {
                        catFileOutput.addFile(fs);
                    }
                    ++count;
                }

                // Generate new cat-file input and retrieve file contents
                catFileInput = new CatFileInputHandler();
                for (SimpleEntry<String, String> bppair : filesToAdd) {
                    catFileInput.addObject(bppair.getKey());
                }
                String[] fileContents = builderFactory.builder(repository)
                    .command("cat-file")
                    .argument("--batch=")
                    .inputHandler(catFileInput)
                    .build(catFileOutput).call();
                if (filesToAdd.size() != fileContents.length) {
                    throw new IndexOutOfBoundsException(
                        "git cat-file --batch= returned wrong number of files");
                }
                count = 0;
                for (SimpleEntry<String, String> bppair : filesToAdd) {
                    String blob = bppair.getKey(), path = bppair.getValue();
                    String fileContent = fileContents[count];
                    if (fileContent != null) {
                        requestBuffer.add(buildAddFileToRef(blob, path)
                            // Upsert inserts a new document into the index if it does not already exist.
                            .setUpsert(jsonBuilder()
                            .startObject()
                                .field("project", repository.getProject().getKey())
                                .field("repository", repository.getSlug())
                                .field("blob", blob)
                                .field("path", path)
                                .field("extension", FilenameUtils.getExtension(path).toLowerCase())
                                .field("contents", fileContent)
                                .field("charcount", fileContent.length())
                                .field("linecount", countLines(fileContent))
                                .startArray("refs")
                                    .value(ref)
                                .endArray()
                            .endObject()));
                    }
                    ++count;
                }
            } catch (Exception e) {
                log.error("Caught error during new file indexing, aborting update", e);
                return;
            }
        }

        // Get deleted commits
        String [] deletedCommits;
        try {
            deletedCommits = builderFactory.builder(repository)
                .command("rev-list")
                .argument(prevHash)
                .argument("^" + newHash)
                .build(new StringOutputHandler()).call()
                .split("\n+");
        } catch (Exception e) {
            log.error("Caught error while scanning for deleted commits, aborting update", e);
            return;
        }

        // Remove deleted commits from ES index
        int commitsDeleted = 0;
        for (String hash : deletedCommits) {
            if (hash.length() != 40) {
                continue;
            }
            requestBuffer.add(buildDeleteCommitFromRef(hash));
            ++commitsDeleted;
        }

        // Get new commits
        String [] newCommits;
        try {
            newCommits = builderFactory.builder(repository)
                .command("log")
                .argument("--format=%H%x02%ct%x02%an%x02%ae%x02%s%x02%b%x03")
                .argument(newHash)
                .argument("^" + prevHash)
                .build(new StringOutputHandler()).call()
                .split("\u0003");
        } catch (Exception e) {
            log.error("Caught error while scanning for new commits, aborting update", e);
            return;
        }

        // Add new commits to ES index
        int commitsAdded = 0;
        for (String line : newCommits) {
            try {
                // Parse each commit "line" (not really lines, since they're delimited by \u0003)
                if (line.length() <= 40) {
                    continue;
                }
                if (line.charAt(0) == '\n') {
                    line = line.substring(1);
                }
                String [] commitToks = line.split("\u0002", 6);
                String hash = commitToks[0];
                long timestamp = Long.parseLong(commitToks[1]) * 1000;
                String authorName = commitToks[2];
                String authorEmail = commitToks[3];
                String subject = commitToks[4];
                String body = commitToks.length < 6 ? "" : commitToks[5]; // bodies are optional, so this might not be present
                if (hash.length() != 40) {
                    continue;
                }

                // Add commit to request
                requestBuffer.add(
                    buildAddCommitToRef(hash)
                    .setUpsert(jsonBuilder()
                    .startObject()
                        .field("project", repository.getProject().getKey())
                        .field("repository", repository.getSlug())
                        .field("hash", hash)
                        .field("commitdate", new Date(timestamp))
                        .field("authorname", authorName)
                        .field("authoremail", authorEmail)
                        .field("subject", subject)
                        .field("body", body)
                        .startArray("refs")
                            .value(ref)
                        .endArray()
                    .endObject())
                );
                ++commitsAdded;
            } catch (Exception e) {
                log.warn("Caught error while constructing request object, skipping update", e);
                continue;
            }
        }

        log.debug("{} update: adding {} commits, deleting {} commits",
            refDesc, commitsAdded, commitsDeleted);

        // Write remaining requests and wait for completion
        requestBuffer.flush();

        // Update latest indexed note
        addLatestIndexedNote(newHash);
    }

    private static final int countLines (String str) {
        char prevChar = '\n', c;
        int count = 0;
        for (int i = 0; i < str.length(); ++i) {
            c = str.charAt(i);
            if ((prevChar == '\r' && c != '\n') || prevChar == '\n') {
                ++count;
            }
            prevChar = c;
        }
        return count;
    }

}
