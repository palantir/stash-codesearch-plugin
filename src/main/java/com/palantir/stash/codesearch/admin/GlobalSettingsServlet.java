/**
 * Codesearch global settings configuration servlet.
 */

package com.palantir.stash.codesearch.admin;

import static com.palantir.stash.codesearch.admin.GlobalSettings.COMMIT_BODY_BOOST_LB;
import static com.palantir.stash.codesearch.admin.GlobalSettings.COMMIT_BODY_BOOST_UB;
import static com.palantir.stash.codesearch.admin.GlobalSettings.COMMIT_HASH_BOOST_LB;
import static com.palantir.stash.codesearch.admin.GlobalSettings.COMMIT_HASH_BOOST_UB;
import static com.palantir.stash.codesearch.admin.GlobalSettings.COMMIT_SUBJECT_BOOST_LB;
import static com.palantir.stash.codesearch.admin.GlobalSettings.COMMIT_SUBJECT_BOOST_UB;
import static com.palantir.stash.codesearch.admin.GlobalSettings.FILE_NAME_BOOST_LB;
import static com.palantir.stash.codesearch.admin.GlobalSettings.FILE_NAME_BOOST_UB;
import static com.palantir.stash.codesearch.admin.GlobalSettings.MAX_CONCURRENT_INDEXING_LB;
import static com.palantir.stash.codesearch.admin.GlobalSettings.MAX_CONCURRENT_INDEXING_UB;
import static com.palantir.stash.codesearch.admin.GlobalSettings.MAX_FILE_SIZE_LB;
import static com.palantir.stash.codesearch.admin.GlobalSettings.MAX_FILE_SIZE_UB;
import static com.palantir.stash.codesearch.admin.GlobalSettings.MAX_FRAGMENTS_LB;
import static com.palantir.stash.codesearch.admin.GlobalSettings.MAX_FRAGMENTS_UB;
import static com.palantir.stash.codesearch.admin.GlobalSettings.MAX_MATCH_LINES_LB;
import static com.palantir.stash.codesearch.admin.GlobalSettings.MAX_MATCH_LINES_UB;
import static com.palantir.stash.codesearch.admin.GlobalSettings.MAX_PREVIEW_LINES_LB;
import static com.palantir.stash.codesearch.admin.GlobalSettings.MAX_PREVIEW_LINES_UB;
import static com.palantir.stash.codesearch.admin.GlobalSettings.PAGE_SIZE_LB;
import static com.palantir.stash.codesearch.admin.GlobalSettings.PAGE_SIZE_UB;
import static com.palantir.stash.codesearch.admin.GlobalSettings.SEARCH_TIMEOUT_LB;
import static com.palantir.stash.codesearch.admin.GlobalSettings.SEARCH_TIMEOUT_UB;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.atlassian.soy.renderer.SoyTemplateRenderer;
import com.atlassian.bitbucket.AuthorisationException;
import com.atlassian.bitbucket.server.ApplicationPropertiesService;
import com.atlassian.bitbucket.user.EscalatedSecurityContext;
import com.atlassian.bitbucket.permission.Permission;
import com.atlassian.bitbucket.permission.PermissionValidationService;
import com.atlassian.bitbucket.user.SecurityService;
import com.atlassian.bitbucket.util.Operation;
import com.google.common.collect.ImmutableMap;
import com.palantir.stash.codesearch.updater.SearchUpdater;

public class GlobalSettingsServlet extends HttpServlet {

    /**
     *
     */
    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(GlobalSettingsServlet.class);

    private final ApplicationPropertiesService propertiesService;

    private final SettingsManager settingsManager;

    private final PermissionValidationService validationService;

    private final SearchUpdater searchUpdater;

    private final SecurityService securityService;

    private final SoyTemplateRenderer soyTemplateRenderer;

    public GlobalSettingsServlet(
        ApplicationPropertiesService propertiesService,
        SettingsManager settingsManager,
        PermissionValidationService validationService,
        SearchUpdater searchUpdater,
        SecurityService securityService,
        SoyTemplateRenderer soyTemplateRenderer) {
        this.propertiesService = propertiesService;
        this.settingsManager = settingsManager;
        this.validationService = validationService;
        this.searchUpdater = searchUpdater;
        this.securityService = securityService;
        this.soyTemplateRenderer = soyTemplateRenderer;
    }

    private static int parseInt(
        String fieldName, int min, int max, String value)
        throws IllegalArgumentException {
        int intValue;
        try {
            intValue = Integer.parseInt(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("\"" + fieldName + "\" must be an integer.");
        }
        if (intValue < min) {
            throw new IllegalArgumentException("\"" + fieldName + "\" must be at least " + min + ".");
        }
        if (intValue > max) {
            throw new IllegalArgumentException("\"" + fieldName + "\" must not exceed " + max + ".");
        }
        return intValue;
    }

    private static double parseDouble(
        String fieldName, double min, double max, String value)
        throws IllegalArgumentException {
        double doubleValue;
        try {
            doubleValue = Double.parseDouble(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("\"" + fieldName + "\" must be a floating-point value.");
        }
        if (doubleValue < min) {
            throw new IllegalArgumentException("\"" + fieldName + "\" must be at least " + min + ".");
        }
        if (doubleValue > max) {
            throw new IllegalArgumentException("\"" + fieldName + "\" must not exceed " + max + ".");
        }
        return doubleValue;
    }

    // Make sure the current user is authenticated and a sysadmin
    private boolean verifySysAdmin(HttpServletRequest req, HttpServletResponse resp)
        throws IOException {
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
            return false;
        }
        try {
            validationService.validateForGlobal(Permission.SYS_ADMIN);
        } catch ( AuthorisationException notSysAdminException) {
            log.warn("User {} is not a system administrator", req.getRemoteUser());
            resp.sendError(HttpServletResponse.SC_UNAUTHORIZED, "You do not have permission to access this page.");
            return false;
        }
        return true;
    }

    private void renderPage(HttpServletRequest req, HttpServletResponse resp,
        GlobalSettings globalSettings, Collection<? extends Object> errors)
        throws ServletException, IOException {
        resp.setContentType("text/html");
        try {
            ImmutableMap<String, Object> data = new ImmutableMap.Builder<String, Object>()
                .put("settings", globalSettings)
                .put("errors", errors)
                .build();
            soyTemplateRenderer.render(resp.getWriter(),
                "com.palantir.stash.stash-code-search:codesearch-soy",
                "plugin.page.codesearch.globalSettingsPage",
                data);
        } catch (Exception e) {
            log.error("Error rendering Soy template", e);
        }

    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
        throws ServletException, IOException {
        if (verifySysAdmin(req, resp)) {
            renderPage(req, resp, settingsManager.getGlobalSettings(), Collections.emptyList());
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
        throws ServletException, IOException {
        if (!verifySysAdmin(req, resp)) {
            return;
        }

        // Parse arguments
        ArrayList<String> errors = new ArrayList<String>();
        boolean indexingEnabled = "on".equals(req.getParameter("indexingEnabled"));
        int maxConcurrentIndexing = 0;
        try {
            maxConcurrentIndexing = parseInt("Indexing Concurrency Limit",
                MAX_CONCURRENT_INDEXING_LB, MAX_CONCURRENT_INDEXING_UB,
                req.getParameter("maxConcurrentIndexing"));
        } catch (IllegalArgumentException e) {
            errors.add(e.getMessage());
        }
        int maxFileSize = 0;
        try {
            maxFileSize = parseInt("Max Filesize", MAX_FILE_SIZE_LB, MAX_FILE_SIZE_UB,
                req.getParameter("maxFileSize"));
        } catch (IllegalArgumentException e) {
            errors.add(e.getMessage());
        }
        int searchTimeout = 0;
        try {
            searchTimeout = parseInt("Search Timeout", SEARCH_TIMEOUT_LB, SEARCH_TIMEOUT_UB,
                req.getParameter("searchTimeout"));
        } catch (IllegalArgumentException e) {
            errors.add(e.getMessage());
        }
        String noHighlightExtensions = req.getParameter("noHighlightExtensions");
        int maxPreviewLines = 0;
        try {
            maxPreviewLines = parseInt("Preview Limit", MAX_PREVIEW_LINES_LB, MAX_PREVIEW_LINES_UB,
                req.getParameter("maxPreviewLines"));
        } catch (IllegalArgumentException e) {
            errors.add(e.getMessage());
        }
        int maxMatchLines = 0;
        try {
            maxMatchLines = parseInt("Match Limit", MAX_MATCH_LINES_LB, MAX_MATCH_LINES_UB,
                req.getParameter("maxMatchLines"));
        } catch (IllegalArgumentException e) {
            errors.add(e.getMessage());
        }
        int maxFragments = 0;
        try {
            maxFragments = parseInt("Fragment Limit", MAX_FRAGMENTS_LB, MAX_FRAGMENTS_UB,
                req.getParameter("maxFragments"));
        } catch (IllegalArgumentException e) {
            errors.add(e.getMessage());
        }
        int pageSize = 0;
        try {
            pageSize = parseInt("Page Size", PAGE_SIZE_LB, PAGE_SIZE_UB,
                req.getParameter("pageSize"));
        } catch (IllegalArgumentException e) {
            errors.add(e.getMessage());
        }
        double commitHashBoost = 0.0;
        try {
            commitHashBoost = parseDouble("Commit Hash Boost", COMMIT_HASH_BOOST_LB, COMMIT_HASH_BOOST_UB,
                req.getParameter("commitHashBoost"));
        } catch (IllegalArgumentException e) {
            errors.add(e.getMessage());
        }
        double commitSubjectBoost = 0.0;
        try {
            commitSubjectBoost = parseDouble("Commit Subject Boost", COMMIT_SUBJECT_BOOST_LB, COMMIT_SUBJECT_BOOST_UB,
                req.getParameter("commitSubjectBoost"));
        } catch (IllegalArgumentException e) {
            errors.add(e.getMessage());
        }
        double commitBodyBoost = 0.0;
        try {
            commitBodyBoost = parseDouble("Commit Body Boost", COMMIT_BODY_BOOST_LB, COMMIT_BODY_BOOST_UB,
                req.getParameter("commitBodyBoost"));
        } catch (IllegalArgumentException e) {
            errors.add(e.getMessage());
        }
        double fileNameBoost = 0.0;
        try {
            fileNameBoost = parseDouble("Filename Boost", FILE_NAME_BOOST_LB, FILE_NAME_BOOST_UB,
                req.getParameter("fileNameBoost"));
        } catch (IllegalArgumentException e) {
            errors.add(e.getMessage());
        }

        // Update settings object iff no parse errors
        GlobalSettings settings;
        if (errors.isEmpty()) {
            settings = settingsManager.setGlobalSettings(indexingEnabled,
                maxConcurrentIndexing, maxFileSize, searchTimeout, noHighlightExtensions,
                maxPreviewLines, maxMatchLines, maxFragments, pageSize, commitHashBoost,
                commitSubjectBoost, commitBodyBoost, fileNameBoost);
            // Trigger reindex is requested
            if ("true".equals(req.getParameter("reindex"))) {
                log.info("User {} submitted an async full reindex", req.getRemoteUser());
                new Thread(new Runnable() {

                    @Override
                    public void run() {
                        try {
                            EscalatedSecurityContext esc =
                                securityService.withPermission(Permission.SYS_ADMIN, "full reindex by sysadmin");

                            esc.call(new Operation<Void, Exception>() {

                                @Override
                                public Void perform() {
                                    searchUpdater.reindexAll();
                                    return null;
                                }
                            });
                        } catch (Exception e) {
                            log.warn("Caught exception while reindexing", e);
                        }
                    }
                }).start();
            }
        } else {
            settings = settingsManager.getGlobalSettings();
        }

        renderPage(req, resp, settings, errors);
    }

}
