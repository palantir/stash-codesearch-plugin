/**
 * Static factory class for building codesearch ES filter objects.
 */

package com.palantir.stash.codesearch.search;

import com.atlassian.stash.repository.Repository;
import com.google.common.collect.Iterators;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import javax.xml.bind.DatatypeConverter;
import org.elasticsearch.index.query.*;
import org.joda.time.ReadableInstant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.elasticsearch.index.query.FilterBuilders.*;

public class SearchFilters {

    private static final Logger log = LoggerFactory.getLogger(SearchFilters.class);

    private static <T> Iterable<T> toIterable (final T[] array) {
        return new Iterable<T>() {
            @Override public Iterator<T> iterator () {
                return Iterators.forArray(array);
            }
        };
    }

    public static FilterBuilder exactRefFilter (String ref) {
        return termFilter("refs.untouched", ref)
            .cache(true)
            .cacheKey("CACHE^EXACTREFFILTER^" + ref);
    }

    public static FilterBuilder projectRepositoryFilter (String project, String repository) {
        return boolFilter()
            .must(termFilter("project", project))
            .must(termFilter("repository", repository))
            .cache(true)
            .cacheKey("CACHE^PROJECTREPOFILTER^" + project + "^" + repository);
    }

    public static FilterBuilder aclFilter (Map<String, Repository> repoMap) {
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
            filterHash = DatatypeConverter.printBase64Binary(hasher.digest());
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

    public static FilterBuilder refFilter (String[] refs) {
        return refFilter(toIterable(refs));
    }

    public static FilterBuilder refFilter (Iterable<String> refs) {
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

    public static FilterBuilder projectFilter (String[] projects) {
        return projectFilter(toIterable(projects));
    }

    public static FilterBuilder projectFilter (Iterable<String> projects) {
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

    public static FilterBuilder repositoryFilter (String[] repositories) {
        return repositoryFilter(toIterable(repositories));
    }

    public static FilterBuilder repositoryFilter (Iterable<String> repositories) {
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

    public static FilterBuilder extensionFilter (String[] extensions) {
        return extensionFilter(toIterable(extensions));
    }

    public static FilterBuilder extensionFilter (Iterable<String> extensions) {
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

    public static FilterBuilder authorFilter (String[] authors) {
        return authorFilter(toIterable(authors));
    }

    public static FilterBuilder authorFilter (Iterable<String> authors) {
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

    public static FilterBuilder dateRangeFilter (ReadableInstant from, ReadableInstant to) {
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
