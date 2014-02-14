/**
 * Default implementation of SearchUpdateJobImpl that incrementally indexes a branch's source code.
 */

package com.palantir.stash.codesearch.updater;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.common.xcontent.XContentBuilder;
import com.atlassian.stash.repository.*;
import com.atlassian.stash.scm.git.*;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.elasticsearch.common.xcontent.XContentFactory.*;
import static org.elasticsearch.index.query.FilterBuilders.*;
import static org.elasticsearch.index.query.QueryBuilders.*;
import static com.palantir.stash.codesearch.elasticsearch.ElasticSearch.*;

class SearchUpdateJobImpl implements SearchUpdateJob {

    private static final Logger log = LoggerFactory.getLogger(SearchUpdateJobImpl.class);

    private static final String EMPTY_TREE = "4b825dc642cb6eb9a060e54bf8d69288fbee4904";

    private static final String AUTHOR_NAME = "Stash Codesearch";

    private static final String AUTHOR_EMAIL = "codesearch@noreply";

    private final Repository repository;

    private final Branch branch;

    public SearchUpdateJobImpl (Repository repository, Branch branch) {
        this.repository = repository;
        this.branch = branch;
    }

    @Override
    public boolean equals (Object o) {
        if (!(o instanceof SearchUpdateJobImpl) || o == null) {
            return false;
        }
        SearchUpdateJobImpl other = (SearchUpdateJobImpl) o;
        return repository.getSlug().equals(other.repository.getSlug()) &&
            repository.getProject().getKey().equals(other.repository.getProject().getKey()) &&
            branch.getId().equals(other.branch.getId());
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
        return getRepoDesc() + "^" + branch.getId();
    }

    @Override
    public Repository getRepository () {
        return repository;
    }

    @Override
    public Branch getBranch () {
        return branch;
    }

    /**
     * For incremental updates, we annotate the latest indexed commit in each branch. The following
     * four methods provide note reading, adding, and deleting functionality.
     */
    private String getNotesRef () {
        return "refs/notes/stashcodesearch/index/" + branch.getId();
    }

    // Returns EMPTY_TREE if no commits were indexed before this.
    private String getLatestIndexedHash (GitCommandBuilderFactory builderFactory) {
        String listOutput;
        try {
            listOutput = builderFactory.builder(repository)
                .command("notes")
                .argument("--ref").argument(getNotesRef())
                .argument("list")
                .build(new StringOutputHandler()).call();
        } catch (Exception e) {
            log.warn("Caught error while searching for notes in ref {}", getNotesRef(), e);
            return EMPTY_TREE;
        }
        String [] toks = listOutput.split("\\s+");
        if (toks.length > 1) {
            return toks[1].length() == 40 ? toks[1] : EMPTY_TREE;
        }
        return EMPTY_TREE;
    }

    // Returns true iff successful.
    private boolean deleteLatestIndexedNote (GitCommandBuilderFactory builderFactory) {
        String prevHash;
        while (!EMPTY_TREE.equals(prevHash = getLatestIndexedHash(builderFactory))) {
            try {
                builderFactory.builder(repository)
                    .command("notes")
                    .argument("--ref").argument(getNotesRef())
                    .argument("remove")
                    .argument(prevHash)
                    .build(new StringOutputHandler()).call();
            } catch (Exception e) {
                log.error("Caught error while deleting note at {}", prevHash, e);
                return false;
            }
        }
        return true;
    }

    // Returns true iff successful
    private boolean addLatestIndexedNote (GitCommandBuilderFactory builderFactory,
            String commitHash) {
        deleteLatestIndexedNote(builderFactory);
        try {
            builderFactory.builder(repository)
                .command("notes")
                .argument("--ref").argument(getNotesRef())
                .argument("add")
                .argument("-m").argument("Latest indexed")
                .argument(commitHash)
                .build(new StringOutputHandler()).call();
        } catch (Exception e) {
            log.error("Caught error while adding note at {}", commitHash, e);
            return false;
        }
        return true;
    }

    // Returns the hash of the latest commit on the job's branch (null if not found)
    private String getLatestHash (GitCommandBuilderFactory builderFactory) {
        try {
            String newHash = builderFactory.builder(repository)
                .command("show-ref")
                .argument("--verify")
                .argument(branch.getId())
                .build(new StringOutputHandler()).call()
                .split("\\s+")[0];
            if (newHash.length() != 40) {
                log.error("Commit hash {} has invalid length", newHash);
                return null;
            }
            return newHash;
        } catch (Exception e) {
            log.error("Caught error while trying to resolve {}", branch.getId(), e);
            return null;
        }
    }

    @Override
    public void doReindex (GitScm gitScm) {
        GitCommandBuilderFactory builderFactory = gitScm.getCommandBuilderFactory();
        deleteLatestIndexedNote(builderFactory);
        /* TODO: delete existing entries
        ES_CLIENT.prepareDeleteByQuery(ES_UPDATEALIAS)
            .setQuery(boolQuery().must("3)
            .execute()
            .actionGet()
        */
        doUpdate(gitScm);
    }

    @Override
    public void doUpdate (GitScm gitScm) {
        GitCommandBuilderFactory builderFactory = gitScm.getCommandBuilderFactory();

        // List of bulk requests to execute sequentially at the end of the method
        List<BulkRequestBuilder> bulkRequests = new ArrayList<BulkRequestBuilder>();

        // Unique identifier for repo
        String repoDesc = getRepoDesc();

        // Unique identifier for branch
        String branchDesc = toString();

        // Hash of latest indexed commit
        String prevHash = getLatestIndexedHash(builderFactory);

        // Hash of latest commit on branch
        String newHash = getLatestHash(builderFactory);
        if (newHash == null) {
            log.error("Aborting since hash is invalid");
            return;
        }

        // Diff for files
        String [] filesChanged;
        try {
            filesChanged = builderFactory.builder(repository)
                .command("diff")
                .argument("--name-only")
                .argument(prevHash).argument(newHash)
                .build(new StringOutputHandler()).call()
                .split("\n+");
        } catch (Exception e) {
            log.error("Caught error while diffing between {} and {}, aborting update",
                prevHash, newHash, e);
            return;
        }

        // Process each changed file
        BulkRequestBuilder bulkFileUpdate = ES_CLIENT.prepareBulk();
        for (String path : filesChanged) {
            if (path == null || path.isEmpty()) {
                continue;
            }
            String fileId = branchDesc + ":/" + path;

            // Read in file contents
            String newFileContents = null;
            try {
                newFileContents = builderFactory.builder(repository)
                    .command("show")
                    .argument(newHash + ":" + path)
                    // TODO: make file size limit configurable in a plugin config page
                    .build(new SourceFileOutputHandler(256 * 1024)).call();
            } catch (Exception e) {
                log.warn("Caught error reading {}:{}, assuming it's been deleted", newHash, path, e);
                continue;
            }

            // File was deleted or disqualified from indexing
            if (newFileContents == null || newFileContents.isEmpty()) {
                bulkFileUpdate.add(
                    ES_CLIENT.prepareDelete(ES_UPDATEALIAS, "file", fileId)
                    .setRouting(repoDesc));

            // File was updated or modified
            } else {
                try {
                    // Get latest commits touching file
                    String[] fileLog = builderFactory.builder(repository)
                        .command("log")
                        .argument("--format=%ct%x02%an%x03%ae")
                        .argument("--max-count=25")
                        .argument(newHash).argument("--").argument(path)
                        .build(new StringOutputHandler()).call()
                        .split("\n+");

                    // Set up ES document
                    XContentBuilder fileData = jsonBuilder().startObject()
                        .field("project", repository.getProject().getKey())
                        .field("repository", repository.getSlug())
                        .field("branch", branch.getId())
                        .field("path", path)
                        .field("content", newFileContents);

                    // Process latest commits touching this file
                    long latestUpdate = 0;
                    Set<String> authors = new LinkedHashSet<String>();
                    for (String line : fileLog) {
                        String[] histToks = line.split("\u0002");
                        if (histToks.length != 2) {
                            continue;
                        }
                        try {
                            latestUpdate = Math.max(latestUpdate, Long.parseLong(histToks[0]) * 1000);
                        } catch (NumberFormatException e) {
                            log.warn("Caught exception while parsing timestamp, skipping changeset", e);
                            continue;
                        }
                        authors.add(histToks[1]);
                    }
                    fileData.field("lastmodified", new Date(latestUpdate));

                    // Add latest authors
                    fileData.startArray("recentauthors");
                    for (String author : authors) {
                        String[] authorToks = author.split("\u0003");
                        fileData.startObject()
                            .field("authorname", authorToks[0])
                            .field("authoremail", authorToks[1])
                        .endObject();
                    }
                    fileData.endArray();

                    // Finalize ES document
                    bulkFileUpdate.add(ES_CLIENT.prepareIndex(ES_UPDATEALIAS, "file", fileId)
                        .setSource(fileData.endObject())
                        .setRouting(repoDesc));
                } catch (Exception e) {
                    log.error("Caught error processing {}'s history, aborting update", path, e);
                    return;
                }
            }
        }
        bulkRequests.add(bulkFileUpdate);

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
        BulkRequestBuilder bulkCommitDelete = ES_CLIENT.prepareBulk();
        for (String hash : deletedCommits) {
            if (hash.length() != 40) {
                continue;
            }
            bulkCommitDelete.add(
                ES_CLIENT.prepareDelete(ES_UPDATEALIAS, "commit", branchDesc + "^" + hash)
                .setRouting(repoDesc));
        }
        bulkRequests.add(bulkCommitDelete);

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
        BulkRequestBuilder bulkCommitAdd = ES_CLIENT.prepareBulk();
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
                bulkCommitAdd.add(
                    ES_CLIENT.prepareIndex(ES_UPDATEALIAS, "commit", branchDesc + "^" + hash)
                    .setSource(jsonBuilder()
                    .startObject()
                        .field("project", repository.getProject().getKey())
                        .field("repository", repository.getSlug())
                        .field("branch", branch.getId())
                        .field("hash", hash)
                        .field("commitdate", new Date(timestamp))
                        .field("authorname", authorName)
                        .field("authoremail", authorEmail)
                        .field("subject", subject)
                        .field("body", body)
                    .endObject())
                    .setRouting(repoDesc)
                );
            } catch (Exception e) {
                log.warn("Caught error while constructing bulk request object, skipping update", e);
                continue;
            }
        }
        bulkRequests.add(bulkCommitAdd);

        // Submit all bulk requests
        try {
            for (BulkRequestBuilder bulkRequest : bulkRequests) {
                if (bulkRequest.numberOfActions() > 0) {
                    bulkRequest.get();
                }
            }
        } catch (Exception e) {
            log.error("Caught error while executing bulk requests, aborting update", e);
        }

        // Update latest indexed note
        addLatestIndexedNote(builderFactory, newHash);
   }

}
