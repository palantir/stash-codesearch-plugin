/**
 * Static factory class for building codesearch ES filter objects.
 */

package com.palantir.stash.codesearch.search;

import static org.elasticsearch.index.query.FilterBuilders.boolFilter;
import static org.elasticsearch.index.query.FilterBuilders.matchAllFilter;
import static org.elasticsearch.index.query.FilterBuilders.orFilter;
import static org.elasticsearch.index.query.FilterBuilders.prefixFilter;
import static org.elasticsearch.index.query.FilterBuilders.rangeFilter;
import static org.elasticsearch.index.query.FilterBuilders.termFilter;
import static org.elasticsearch.index.query.FilterBuilders.typeFilter;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;

import org.apache.commons.codec.binary.Base64;
import org.elasticsearch.index.query.BoolFilterBuilder;
import org.elasticsearch.index.query.FilterBuilder;
import org.elasticsearch.index.query.RangeFilterBuilder;
import org.joda.time.ReadableInstant;
import org.slf4j.Logger;

import com.atlassian.stash.repository.Repository;
import com.google.common.collect.Iterators;
import com.palantir.stash.codesearch.logger.PluginLoggerFactory;

public class SearchFilterUtils {

    private final Logger log;

    public SearchFilterUtils(PluginLoggerFactory plf) {
        this.log = plf.getLogger(this.getClass().toString());
    }

    private <T> Iterable<T> toIterable(final T[] array) {
        return new Iterable<T>() {

            @Override
            public Iterator<T> iterator() {
                return Iterators.forArray(array);
            }
        };
    }

    public FilterBuilder exactRefFilter(String ref) {
        return termFilter("refs.untouched", ref)
            .cache(true)
            .cacheKey("CACHE^EXACTREFFILTER^" + ref);
    }

    public FilterBuilder projectRepositoryFilter(String project, String repository) {
        return boolFilter()
            .must(termFilter("project", project))
            .must(termFilter("repository", repository))
            .cache(true)
            .cacheKey("CACHE^PROJECTREPOFILTER^" + project + "^" + repository);
    }

    public FilterBuilder aclFilter(Map<String, Repository> repoMap) {
        if (repoMap.isEmpty()) {
            return boolFilter().mustNot(matchAllFilter());
        }

        // Compute cryptographic hash of repository set to use for cache key
        String[] projectRepoPairs = repoMap.keySet().toArray(new String[repoMap.size()]);
        Arrays.sort(projectRepoPairs);
        String filterHash;
        try {
            MessageDigest hasher = MessageDigest.getInstance("SHA-256");
            for (String pair : projectRepoPairs) {
                hasher.update(pair.getBytes());
                hasher.update((byte) 0);
            }
            filterHash = new String(Base64.encodeBase64(hasher.digest()));
        } catch (Exception e) {
            filterHash = null;
            log.error("Caught exception generating ACL hash -- caching is disabled.", e);
        }

        // Create disjunction of individual repo ACL filters
        BoolFilterBuilder filter = boolFilter();
        if (filterHash != null) {
            filter.cache(true)
                .cacheKey("CACHE^ACLORFILTER^" + filterHash);
        } else {
            filter.cache(false);
        }
        for (Repository repo : repoMap.values()) {
            filter.should(projectRepositoryFilter(repo.getProject().getKey(), repo.getSlug()));
        }
        return filter;
    }

    public FilterBuilder refFilter(String[] refs) {
        return refFilter(toIterable(refs));
    }

    public FilterBuilder refFilter(Iterable<String> refs) {
        boolean filterAdded = false;
        BoolFilterBuilder filter = boolFilter();
        for (String ref : refs) {
            String[] toks = ref.split("[/\\s]+");
            // Make sure there's at least one non-empty token
            boolean emptyTokens = true;
            for (String tok : toks) {
                if (!tok.isEmpty()) {
                    emptyTokens = false;
                    break;
                }
            }
            if (emptyTokens) {
                continue;
            }

            BoolFilterBuilder refFilter = boolFilter()
                .cache(true)
                .cacheKey("CACHE^REFANDFILTER^" + ref);
            for (String tok : toks) {
                if (!tok.isEmpty()) {
                    refFilter.must(termFilter("refs", tok.toLowerCase()));
                }
            }
            filter.should(refFilter);
            filterAdded = true;
        }
        return filterAdded ? filter : matchAllFilter();
    }

    public FilterBuilder personalFilter() {
        BoolFilterBuilder filter = boolFilter();
        filter.mustNot(prefixFilter("project", "~")
                .cache(true));
        return filter;
    }

    public FilterBuilder projectFilter(String[] projects) {
        return projectFilter(toIterable(projects));
    }

    public FilterBuilder projectFilter(Iterable<String> projects) {
        boolean filterAdded = false;
        BoolFilterBuilder filter = boolFilter();
        for (String project : projects) {
            project = project.trim();
            if (project.isEmpty()) {
                continue;
            }
            filter.should(termFilter("project", project)
                .cache(true)
                .cacheKey("CACHE^PROJECTFILTER^" + project));
            filterAdded = true;
        }
        return filterAdded ? filter : matchAllFilter();
    }

    public FilterBuilder repositoryFilter(String[] repositories) {
        return repositoryFilter(toIterable(repositories));
    }

    public FilterBuilder repositoryFilter(Iterable<String> repositories) {
        boolean filterAdded = false;
        BoolFilterBuilder filter = boolFilter();
        for (String repository : repositories) {
            repository = repository.trim();
            if (repository.isEmpty()) {
                continue;
            }
            filter.should(termFilter("repository", repository)
                .cache(true)
                .cacheKey("CACHE^REPOFILTER^" + repository));
            filterAdded = true;
        }
        return filterAdded ? filter : matchAllFilter();
    }

    public FilterBuilder extensionFilter(String[] extensions) {
        return extensionFilter(toIterable(extensions));
    }

    public FilterBuilder extensionFilter(Iterable<String> extensions) {
        boolean filterAdded = false;
        BoolFilterBuilder filter = boolFilter();
        for (String extension : extensions) {
            extension = extension.trim();
            if (extension.isEmpty()) {
                continue;
            }
            filter.should(termFilter("extension", extension)
                .cache(true)
                .cacheKey("CACHE^EXTENSIONFILTER^" + extension));
            filterAdded = true;
        }
        return filterAdded ? filter.should(typeFilter("commit")) : matchAllFilter();
    }

    public FilterBuilder authorFilter(String[] authors) {
        return authorFilter(toIterable(authors));
    }

    public FilterBuilder authorFilter(Iterable<String> authors) {
        boolean filterAdded = false;
        BoolFilterBuilder filter = boolFilter();
        for (String author : authors) {
            String[] toks = author.split("\\W+");
            boolean emptyTokens = true;
            for (String tok : toks) {
                if (!tok.isEmpty()) {
                    emptyTokens = false;
                    break;
                }
            }
            if (emptyTokens) {
                continue;
            }

            // Name filters
            BoolFilterBuilder nameFilter = boolFilter();
            for (String tok : toks) {
                if (!tok.isEmpty()) {
                    nameFilter.must(termFilter("commit.authorname", tok.toLowerCase()));
                }
            }
            filter.should(nameFilter);

            // Email filters
            BoolFilterBuilder emailFilter = boolFilter();
            for (String tok : toks) {
                if (!tok.isEmpty()) {
                    emailFilter.must(termFilter("commit.authoremail", tok.toLowerCase()));
                }
            }
            filter.should(emailFilter);
            filterAdded = true;
        }
        return filterAdded ? filter.should(typeFilter("file")) : matchAllFilter();
    }

    public FilterBuilder dateRangeFilter(ReadableInstant from, ReadableInstant to) {
        if (from == null && to == null) {
            return matchAllFilter();
        }
        RangeFilterBuilder dateFilter = rangeFilter("commit.commitdate");
        if (from != null) {
            dateFilter.gte(from.getMillis());
        }
        if (to != null) {
            dateFilter.lte(to.getMillis());
        }
        // Match all files as well, since they don't have date info (user can turn off by
        // unchecking "search files" option.)
        return orFilter(dateFilter, typeFilter("file"));
    }

}
