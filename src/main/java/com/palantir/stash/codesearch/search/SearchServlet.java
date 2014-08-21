/**
 * Servlet for the main search application.
 */

package com.palantir.stash.codesearch.search;

import static com.palantir.stash.codesearch.elasticsearch.ElasticSearch.ES_SEARCHALIAS;
import static org.elasticsearch.index.query.FilterBuilders.andFilter;
import static org.elasticsearch.index.query.FilterBuilders.boolFilter;
import static org.elasticsearch.index.query.FilterBuilders.matchAllFilter;
import static org.elasticsearch.index.query.FilterBuilders.typeFilter;
import static org.elasticsearch.index.query.QueryBuilders.filteredQuery;
import static org.elasticsearch.index.query.QueryBuilders.matchAllQuery;
import static org.elasticsearch.index.query.QueryBuilders.queryString;
import static org.elasticsearch.search.aggregations.AggregationBuilders.cardinality;
import static org.elasticsearch.search.aggregations.AggregationBuilders.extendedStats;
import static org.elasticsearch.search.aggregations.AggregationBuilders.filter;
import static org.elasticsearch.search.aggregations.AggregationBuilders.percentiles;
import static org.elasticsearch.search.aggregations.AggregationBuilders.terms;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.elasticsearch.action.search.SearchPhaseExecutionException;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.ShardSearchFailure;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.query.FilterBuilder;
import org.elasticsearch.index.query.FilteredQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryStringQueryBuilder;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.aggregations.Aggregations;
import org.elasticsearch.search.aggregations.bucket.filter.Filter;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.aggregations.metrics.cardinality.Cardinality;
import org.elasticsearch.search.aggregations.metrics.percentiles.Percentiles;
import org.elasticsearch.search.aggregations.metrics.stats.extended.ExtendedStats;
import org.elasticsearch.search.highlight.HighlightField;
import org.joda.time.DateTimeZone;
import org.joda.time.MutableDateTime;
import org.joda.time.ReadableInstant;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.atlassian.soy.renderer.SoyTemplateRenderer;
import com.atlassian.stash.exception.AuthorisationException;
import com.atlassian.stash.repository.Repository;
import com.atlassian.stash.server.ApplicationPropertiesService;
import com.atlassian.stash.user.Permission;
import com.atlassian.stash.user.PermissionValidationService;
import com.atlassian.webresource.api.assembler.PageBuilderService;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.palantir.stash.codesearch.admin.GlobalSettings;
import com.palantir.stash.codesearch.admin.SettingsManager;
import com.palantir.stash.codesearch.elasticsearch.ElasticSearch;
import com.palantir.stash.codesearch.repository.RepositoryServiceManager;

public class SearchServlet extends HttpServlet {

    /**
     * 
     */
    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(SearchServlet.class);

    private static final DateTimeFormatter TIME_PARSER = ISODateTimeFormat.dateTime();

    private static final DateTimeFormatter DATE_PARSER = ISODateTimeFormat.date();

    private static final DateTimeFormatter TIME_PRINTER = DateTimeFormat.forPattern("YYYY-MM-dd HH:mm");

    private static final double[] PERCENTILES = { 1.0, 5.0, 25.0, 50.0, 75.0, 95.0, 99.0 };

    private final ApplicationPropertiesService propertiesService;

    private final ElasticSearch es;

    private final SettingsManager settingsManager;

    private final PermissionValidationService validationService;

    private final RepositoryServiceManager repositoryServiceManager;

    private final SoyTemplateRenderer soyTemplateRenderer;

    private final PageBuilderService pbs;

    public SearchServlet(
        ApplicationPropertiesService propertiesService,
        ElasticSearch es,
        SettingsManager settingsManager,
        PermissionValidationService validationService,
        RepositoryServiceManager repositoryServiceManager,
        SoyTemplateRenderer soyTemplateRenderer,
        PageBuilderService pbs) {
        this.propertiesService = propertiesService;
        this.es = es;
        this.settingsManager = settingsManager;
        this.validationService = validationService;
        this.repositoryServiceManager = repositoryServiceManager;
        this.soyTemplateRenderer = soyTemplateRenderer;
        this.pbs = pbs;
    }

    private String getStringFromMap(Map<String, ? extends Object> map, String key) {
        Object o = map.get(key);
        if (o == null) {
            return "";
        }
        return o.toString();
    }

    private String getDateStringFromMap(Map<String, ? extends Object> map, String key) {
        Object o = map.get(key);
        if (o == null) {
            return "";
        }
        try {
            return TIME_PRINTER.print(TIME_PARSER.parseDateTime(o.toString()));
        } catch (Exception e) {
            return "";
        }
    }

    // Returns map view of search hits for soy templates
    // Not sure this is actually safe at all.  Jerry thought so.
    @SuppressWarnings("unchecked")
    private ImmutableMap<String, Object> searchHitToDataMap(
        SearchHit hit,
        Map<String, Repository> repoMap, // null iff no permission validation required
        int maxPreviewLines,
        int maxMatchLines,
        ImmutableSet<String> noHighlight) {
        ImmutableMap.Builder<String, Object> hitData = new ImmutableMap.Builder<String, Object>();
        Map<String, Object> hitSource = hit.getSource();

        String type = hit.getType();
        hitData.put("type", type);

        String project = getStringFromMap(hitSource, "project");
        hitData.put("project", project);

        String repository = getStringFromMap(hitSource, "repository");
        hitData.put("repository", repository);

        // Validate permissions & build hit data map
        String repoId = project + "^" + repository;
        Repository repoObject;
        if (repoMap == null) { // current user is system administrator
            repoObject = repositoryServiceManager.getRepositoryService().getBySlug(
                project, repository);
        } else { // must validate against allowed repositories for non-administrators
            repoObject = repoMap.get(repoId);
        }

        if (repoObject != null &&
            repoObject.getProject().getKey().equals(project) &&
            repoObject.getSlug().equals(repository)) {

            // Generate refs array
            ImmutableSortedSet<String> refSet;
            try {
                refSet = ImmutableSortedSet.copyOf((Iterable<String>) hitSource.get("refs"));
            } catch (Exception e) {
                log.warn("Invalid refs collection detected for element in {}/{}", project, repository, e);
                return null;
            }
            if (refSet.isEmpty()) {
                log.warn("Detected empty refs collection for element in {}/{}", project, repository);
                return null;
            }
            hitData.put("refs", refSet);

            // Human-readable labels
            hitData
                .put("projectname", repoObject.getProject().getName())
                .put("repositoryname", repoObject.getName());

            if (type.equals("commit")) {
                hitData
                    .put("hash", getStringFromMap(hitSource, "hash"))
                    .put("subject", getStringFromMap(hitSource, "subject"))
                    .put("body", getStringFromMap(hitSource, "body"))
                    .put("commitDate", getDateStringFromMap(hitSource, "commitdate"))
                    .put("authorName", getStringFromMap(hitSource, "authorname"))
                    .put("authorEmail", getStringFromMap(hitSource, "authoremail"));

            } else if (type.equals("file")) {
                HighlightField highlightField = hit.getHighlightFields().get("contents");
                String path = getStringFromMap(hitSource, "path");
                String primaryRef = "refs/heads/master";
                if (!refSet.contains(primaryRef)) {
                    primaryRef = refSet.iterator().next();
                }
                String contents = getStringFromMap(hitSource, "contents");
                SourceSearch searchedContents = SourceSearch.search(
                    contents, highlightField, 1, maxPreviewLines, maxMatchLines);
                String extension = getStringFromMap(hitSource, "extension");

                hitData
                    .put("path", path)
                    .put("blob", getStringFromMap(hitSource, "blob"))
                    .put("primaryRef", primaryRef)
                    .put("sourceLines", searchedContents.getJoinedLines())
                    .put("sourceLineNums", searchedContents.getJoinedLineNums())
                    .put("isPreview", searchedContents.isPreview())
                    .put("shownLines", searchedContents.getLines().length)
                    .put("excessLines", searchedContents.getExcess())
                    .put("extension", extension)
                    .put("noHighlight", noHighlight.contains(extension));
            }
        } else {
            return null;
        }

        return hitData.build();
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
        throws ServletException, IOException {

        // Make sure user is logged in
        try {
            validationService.validateAuthenticated();
        } catch (AuthorisationException notLoggedInException) {
            try {
                resp.sendRedirect(propertiesService.getLoginUri(URI.create(req.getRequestURL() +
                    (req.getQueryString() == null ? "" : "?" + req.getQueryString())
                    )).toASCIIString());
            } catch (Exception e) {
                log.error("Unable to redirect unauthenticated user to login page", e);
            }
            return;
        }

        // Query and parse settings
        SearchParams params = SearchParams.getParams(
            req, DateTimeZone.forTimeZone(propertiesService.getDefaultTimeZone()));
        GlobalSettings globalSettings = settingsManager.getGlobalSettings();
        ImmutableSet.Builder<String> noHighlightBuilder = new ImmutableSet.Builder<String>();
        for (String extension : globalSettings.getNoHighlightExtensions().split(",")) {
            extension = extension.trim().toLowerCase();
            if (!extension.isEmpty()) {
                noHighlightBuilder.add(extension);
            }
        }
        ImmutableSet<String> noHighlight = noHighlightBuilder.build();
        int maxPreviewLines = globalSettings.getMaxPreviewLines();
        int maxMatchLines = globalSettings.getMaxMatchLines();
        int maxFragments = globalSettings.getMaxFragments();
        int pageSize = globalSettings.getPageSize();
        TimeValue searchTimeout = new TimeValue(globalSettings.getSearchTimeout());
        float commitHashBoost = (float) globalSettings.getCommitHashBoost();
        float commitSubjectBoost = (float) globalSettings.getCommitBodyBoost();
        float commitBodyBoost = (float) globalSettings.getCommitBodyBoost();
        float fileNameBoost = (float) globalSettings.getFileNameBoost();

        // Execute ES query
        int pages = 0;
        long totalHits = 0;
        long searchTime = 0;
        SearchHit[] currentHits = {};
        String error = "";
        ArrayList<ImmutableMap<String, Object>> hitArray =
            new ArrayList<ImmutableMap<String, Object>>(currentHits.length);
        ImmutableMap<String, Object> statistics = ImmutableMap.of();
        if (params.doSearch) {
            // Repo map is null iff user is a system administrator (don't need to validate permissions).
            ImmutableMap<String, Repository> repoMap;
            try {
                validationService.validateForGlobal(Permission.SYS_ADMIN);
                repoMap = null;
            } catch (AuthorisationException e) {
                repoMap = repositoryServiceManager.getRepositoryMap(
                    validationService);
                if (repoMap.isEmpty()) {
                    error = "You do not have permissions to access any repositories";
                }
            }

            int startIndex = params.page * pageSize;
            SearchRequestBuilder esReq = es.getClient().prepareSearch(ES_SEARCHALIAS)
                .setFrom(startIndex)
                .setSize(pageSize)
                .setTimeout(searchTimeout)
                .setFetchSource(true);

            if (error != null && !error.isEmpty()) {
                log.warn("Not performing search due to error {}", error);

            } else {
                // Build query source and perform query
                QueryBuilder query = matchAllQuery();
                if (params.searchString != null && !params.searchString.isEmpty()) {
                    QueryStringQueryBuilder queryStringQuery = queryString(params.searchString)
                        .analyzeWildcard(true)
                        .lenient(true)
                        .defaultOperator(QueryStringQueryBuilder.Operator.AND);
                    if (params.searchCommits) {
                        queryStringQuery
                            .field("commit.subject", commitSubjectBoost)
                            .field("commit.hash", commitHashBoost)
                            .field("commit.body", commitBodyBoost);
                    }
                    if (params.searchFilenames) {
                        queryStringQuery.field("file.path", fileNameBoost);
                    }
                    if (params.searchCode) {
                        queryStringQuery.field("file.contents", 1);
                    }
                    query = queryStringQuery;
                }
                FilterBuilder filter = andFilter(
                    boolFilter().must(
                        repoMap == null ? matchAllFilter() : SearchFilters.aclFilter(repoMap),
                        SearchFilters.refFilter(params.refNames.split(",")),
                        SearchFilters.projectFilter(params.projectKeys.split(",")),
                        SearchFilters.repositoryFilter(params.repoNames.split(",")),
                        SearchFilters.extensionFilter(params.extensions.split(",")),
                        SearchFilters.authorFilter(params.authorNames.split(","))
                        ),
                    SearchFilters.dateRangeFilter(params.committedAfter, params.committedBefore));
                FilteredQueryBuilder finalQuery = filteredQuery(query, filter);
                esReq.setQuery(finalQuery)
                    .setHighlighterPreTags("\u0001")
                    .setHighlighterPostTags("\u0001")
                    .addHighlightedField("contents", 1, maxFragments);

                String[] typeArray = {};
                if (params.searchCommits) {
                    if (params.searchFilenames || params.searchCode) {
                        typeArray = new String[] { "commit", "file" };
                    } else {
                        typeArray = new String[] { "commit" };
                    }
                } else if (params.searchFilenames || params.searchCode) {
                    typeArray = new String[] { "file" };
                }
                esReq.setTypes(typeArray);

                // Build aggregations if statistics were requested
                if (params.showStatistics) {
                    esReq
                        .addAggregation(cardinality("authorCardinality").field("authoremail.untouched")
                            .precisionThreshold(1000))
                        .addAggregation(terms("authorRanking").field("authoremail.untouched")
                            .size(25))
                        .addAggregation(percentiles("charcountPercentiles").field("charcount")
                            .percentiles(PERCENTILES))
                        .addAggregation(extendedStats("charcountStats").field("charcount"))
                        .addAggregation(filter("commitCount").filter(typeFilter("commit")))
                        .addAggregation(cardinality("extensionCardinality").field("extension")
                            .precisionThreshold(1000))
                        .addAggregation(terms("extensionRanking").field("extension")
                            .size(25))
                        .addAggregation(percentiles("linecountPercentiles").field("linecount")
                            .percentiles(PERCENTILES))
                        .addAggregation(extendedStats("linecountStats").field("linecount"));
                }

                SearchResponse esResp = null;
                try {
                    esResp = esReq.get();
                } catch (SearchPhaseExecutionException e) {
                    log.warn("Query failure", e);
                    error = "Make sure your query conforms to the Lucene/Elasticsearch query string syntax.";
                }

                if (esResp != null) {
                    SearchHits esHits = esResp.getHits();
                    totalHits = esHits.getTotalHits();
                    pages = (int) Math.min(Integer.MAX_VALUE, (totalHits + pageSize - 1) / pageSize);
                    currentHits = esHits.getHits();
                    searchTime = esResp.getTookInMillis();
                    for (ShardSearchFailure failure : esResp.getShardFailures()) {
                        log.warn("Shard failure {}", failure.reason());
                        if (error == null || error.isEmpty()) {
                            error = "Shard failure: " + failure.reason();
                        }
                    }
                    Aggregations aggs = esResp.getAggregations();
                    if (params.showStatistics && aggs != null && !aggs.asList().isEmpty()) {
                        Cardinality authorCardinality = aggs.get("authorCardinality");
                        Terms authorRanking = aggs.get("authorRanking");
                        Percentiles charcountPercentiles = aggs.get("charcountPercentiles");
                        Filter commitCount = aggs.get("commitCount");
                        ExtendedStats charcountStats = aggs.get("charcountStats");
                        Cardinality extensionCardinality = aggs.get("extensionCardinality");
                        Terms extensionRanking = aggs.get("extensionRanking");
                        Percentiles linecountPercentiles = aggs.get("linecountPercentiles");
                        ExtendedStats linecountStats = aggs.get("linecountStats");
                        statistics = new ImmutableMap.Builder<String, Object>()
                            .put("authorCardinality", authorCardinality.getValue())
                            .put("authorRanking", getSoyRankingList(
                                authorRanking, commitCount.getDocCount()))
                            .put("charcount", new ImmutableMap.Builder<String, Object>()
                                .put("average", charcountStats.getAvg())
                                .put("max", Math.round(charcountStats.getMax()))
                                .put("min", Math.round(charcountStats.getMin()))
                                .put("percentiles", getSoyPercentileList(
                                    charcountPercentiles, PERCENTILES))
                                .put("sum", Math.round(charcountStats.getSum()))
                                .build())
                            .put("commitcount", commitCount.getDocCount())
                            .put("extensionCardinality", extensionCardinality.getValue())
                            .put("extensionRanking", getSoyRankingList(
                                extensionRanking, charcountStats.getCount()))
                            .put("filecount", charcountStats.getCount())
                            .put("linecount", new ImmutableMap.Builder<String, Object>()
                                .put("average", linecountStats.getAvg())
                                .put("max", Math.round(linecountStats.getMax()))
                                .put("min", Math.round(linecountStats.getMin()))
                                .put("percentiles", getSoyPercentileList(
                                    linecountPercentiles, PERCENTILES))
                                .put("sum", Math.round(linecountStats.getSum()))
                                .build())
                            .build();
                    }
                }

            }

            // Iterate through current page of search hits
            for (SearchHit hit : currentHits) {
                ImmutableMap<String, Object> hitData = searchHitToDataMap(
                    hit, repoMap, maxPreviewLines, maxMatchLines, noHighlight);
                if (hitData != null) {
                    hitArray.add(hitData);
                }
            }
        }

        // Render page
        pbs.assembler().resources().requireContext("com.atlassian.auiplugin:aui-date-picker");
        pbs.assembler().resources().requireContext("com.atlassian.auiplugin:aui-experimental-tooltips");
        resp.setContentType("text/html");
        try {
            String queryString = req.getQueryString();
            String fullUri = req.getRequestURI() + "?" +
                (queryString == null ? "" : queryString.replaceAll("&?page=\\d*", ""));
            ImmutableMap<String, Object> data = new ImmutableMap.Builder<String, Object>()
                .put("pages", pages)
                .put("currentPage", params.page)
                .put("prevParams", params.soyParams)
                .put("doSearch", params.doSearch)
                .put("totalHits", totalHits)
                .put("hitArray", hitArray)
                .put("statistics", statistics)
                .put("error", error)
                .put("fullUri", fullUri)
                .put("baseUrl", propertiesService.getBaseUrl())
                .put("resultFrom", Math.min(totalHits, params.page * pageSize + 1))
                .put("resultTo", Math.min(totalHits, (params.page + 1) * pageSize))
                .put("searchTime", searchTime)
                .build();
            soyTemplateRenderer.render(resp.getWriter(),
                "com.palantir.stash.stash-code-search:codesearch-soy",
                "plugin.page.codesearch.searchPage",
                data);
        } catch (Exception e) {
            log.error("Error rendering Soy template", e);
        }
    }

    // Generates a list of percentile aggregation entries for Soy
    private static ImmutableList<ImmutableMap<String, Object>> getSoyPercentileList(
        Percentiles aggregation, double... percentiles) {
        ImmutableList.Builder<ImmutableMap<String, Object>> builder = ImmutableList.builder();
        for (double percentile : percentiles) {
            builder.add(ImmutableMap.<String, Object> of(
                "percentile", percentile,
                "value", aggregation.percentile(percentile)));
        }
        return builder.build();
    }

    // Generate a list of ranking aggregation entries for Soy
    private static ImmutableList<ImmutableMap<String, Object>> getSoyRankingList(
        Terms aggregation, long totalCount) {
        ImmutableList.Builder<ImmutableMap<String, Object>> builder = ImmutableList.builder();
        long otherCount = totalCount;
        for (Terms.Bucket bucket : aggregation.getBuckets()) {
            String key = bucket.getKey();
            long count = bucket.getDocCount();
            otherCount -= count;
            builder.add(ImmutableMap.<String, Object> of(
                "key", key,
                "count", count,
                "proportion", ((double) count) / totalCount));
        }
        if (otherCount > 0) {
            builder.add(ImmutableMap.<String, Object> of(
                "other", true,
                "key", "Other",
                "count", otherCount,
                "proportion", ((double) otherCount / totalCount)));
        }
        return builder.build();
    }

    // Utility class for parsing and storing search parameters from an http request
    private static class SearchParams {

        public final boolean doSearch;
        public final String searchString;
        public final boolean showStatistics;
        public final boolean searchCode;
        public final boolean searchFilenames;
        public final boolean searchCommits;
        public final String projectKeys;
        public final String repoNames;
        public final String refNames;
        public final String extensions;
        public final String authorNames;
        public final int page;
        public final ReadableInstant committedAfter;
        public final ReadableInstant committedBefore;
        public final ImmutableMap<String, Object> soyParams;

        private SearchParams(boolean doSearch, String searchString, boolean showStatistics,
            boolean searchCode, boolean searchFilenames, boolean searchCommits,
            String projectKeys, String repoNames, String refNames, String extensions,
            String authorNames, int page, String committedAfterStr, String committedBeforeStr,
            DateTimeZone tz) {
            this.doSearch = doSearch;
            this.searchString = searchString;
            this.showStatistics = showStatistics;
            this.searchCode = searchCode;
            this.searchFilenames = searchFilenames;
            this.searchCommits = searchCommits;
            this.projectKeys = projectKeys;
            this.repoNames = repoNames;
            this.refNames = refNames;
            this.extensions = extensions;
            this.authorNames = authorNames;
            this.page = page;
            ImmutableMap.Builder<String, Object> paramBuilder = new ImmutableMap.Builder<String, Object>()
                .put("searchString", searchString)
                .put("showStatistics", showStatistics)
                .put("searchCode", searchCode)
                .put("searchFilenames", searchFilenames)
                .put("searchCommits", searchCommits)
                .put("projectKeys", projectKeys)
                .put("repoNames", repoNames)
                .put("refNames", refNames)
                .put("extensions", extensions)
                .put("authorNames", authorNames)
                .put("committedAfter", committedAfterStr)
                .put("committedBefore", committedBeforeStr)
                .put("page", page);
            this.soyParams = paramBuilder.build();

            MutableDateTime afterDateTime;
            try {
                afterDateTime = DATE_PARSER.withZone(tz).parseMutableDateTime(committedAfterStr);
                afterDateTime.setSecondOfDay(0);
                afterDateTime.setMillisOfSecond(0);
            } catch (Exception e) {
                afterDateTime = null;
            }
            this.committedAfter = afterDateTime;

            MutableDateTime beforeDateTime;
            try {
                beforeDateTime = DATE_PARSER.withZone(tz).parseMutableDateTime(committedBeforeStr);
                beforeDateTime.setHourOfDay(23);
                beforeDateTime.setMinuteOfHour(59);
                beforeDateTime.setSecondOfMinute(59);
                beforeDateTime.setMillisOfSecond(999);
            } catch (Exception e) {
                beforeDateTime = null;
            }
            this.committedBefore = beforeDateTime;
        }

        public static SearchParams getParams(HttpServletRequest req, DateTimeZone tz) {
            boolean doSearch = true;

            String searchString = req.getParameter("searchString");
            if (searchString == null) {
                doSearch = false;
                searchString = "";
            }

            boolean showStatistics = "true".equals(req.getParameter("showStatistics"));

            boolean searchCode = "on".equals(req.getParameter("searchCode"));
            boolean searchFilenames = "on".equals(req.getParameter("searchFilenames"));
            boolean searchCommits = "on".equals(req.getParameter("searchCommits"));
            if (!searchFilenames && !searchCode && !searchCommits) {
                // Reset checkboxes
                searchFilenames = searchCode = searchCommits = true;
            }

            String projectKeys = req.getParameter("projectKeys");
            if (projectKeys == null) {
                projectKeys = "";
            }

            String repoNames = req.getParameter("repoNames");
            if (repoNames == null) {
                repoNames = "";
            }

            String refNames = req.getParameter("refNames");
            if (refNames == null) {
                refNames = "";
            }

            String extensions = req.getParameter("extensions");
            if (extensions == null) {
                extensions = "";
            }

            String authorNames = req.getParameter("authorNames");
            if (authorNames == null) {
                authorNames = "";
            }

            String committedAfterStr = req.getParameter("committedAfter");
            if (committedAfterStr == null) {
                committedAfterStr = "";
            }

            String committedBeforeStr = req.getParameter("committedBefore");
            if (committedBeforeStr == null) {
                committedBeforeStr = "";
            }

            int page;
            try {
                page = Math.max(0, Integer.parseInt(req.getParameter("page")));
            } catch (Exception e) {
                page = 0;
            }

            return new SearchParams(doSearch, searchString, showStatistics, searchCode,
                searchFilenames, searchCommits, projectKeys, repoNames, refNames, extensions,
                authorNames, page, committedAfterStr, committedBeforeStr, tz);
        }
    }

}
