/**
 * Codesearch repository settings configuration servlet.
 */

package com.palantir.stash.codesearch.admin;

import com.atlassian.soy.renderer.SoyTemplateRenderer;
import com.atlassian.stash.exception.AuthorisationException;
import com.atlassian.stash.repository.*;
import com.atlassian.stash.server.ApplicationPropertiesService;
import com.atlassian.stash.user.*;
import com.atlassian.stash.util.Operation;
import com.google.common.collect.ImmutableMap;
import com.palantir.stash.codesearch.updater.SearchUpdater;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.regex.Pattern;
import javax.servlet.*;
import javax.servlet.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.palantir.stash.codesearch.admin.RepositorySettings.*;

public class RepositorySettingsServlet extends HttpServlet {

    private static final Logger log = LoggerFactory.getLogger(RepositorySettingsServlet.class);

    private final ApplicationPropertiesService propertiesService;

    private final SettingsManager settingsManager;

    private final PermissionValidationService validationService;

    private final RepositoryService repositoryService;

    private final SearchUpdater searchUpdater;

    private final SecurityService securityService;

    private final SoyTemplateRenderer soyTemplateRenderer;

    public RepositorySettingsServlet (
            ApplicationPropertiesService propertiesService,
            SettingsManager settingsManager,
            PermissionValidationService validationService,
            RepositoryService repositoryService,
            SearchUpdater searchUpdater,
            SecurityService securityService,
            SoyTemplateRenderer soyTemplateRenderer) {
        this.propertiesService = propertiesService;
        this.settingsManager = settingsManager;
        this.validationService = validationService;
        this.repositoryService = repositoryService;
        this.searchUpdater = searchUpdater;
        this.securityService = securityService;
        this.soyTemplateRenderer = soyTemplateRenderer;
    }

    // Make sure the current user is authenticated
    private boolean verifyLoggedIn (HttpServletRequest req, HttpServletResponse resp)
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
        return true;
    }

    // Make sure the current user is a repo admin
    private boolean verifyRepoAdmin (HttpServletRequest req, HttpServletResponse resp,
            Repository repository) throws IOException {
        try {
            validationService.validateForRepository(repository, Permission.REPO_ADMIN);
        } catch (AuthorisationException notRepoAdminException) {
            log.warn("User {} is not a repo administrator of {}/{}",
                req.getRemoteUser(), repository.getProject().getKey(), repository.getSlug());
            resp.sendError(HttpServletResponse.SC_UNAUTHORIZED, "You do not have permission to access this page.");
            return false;
        }
        return true;
    }

    private void renderPage (HttpServletRequest req, HttpServletResponse resp,
            Repository repository, RepositorySettings repositorySettings,
            Collection<? extends Object> errors) throws ServletException, IOException {
        resp.setContentType("text/html");
        try {
            ImmutableMap<String, Object> data = new ImmutableMap.Builder<String, Object>()
                .put("repository", repository)
                .put("settings", repositorySettings)
                .put("errors", errors)
                .build();
            soyTemplateRenderer.render(resp.getWriter(),
                "com.palantir.stash.stash-code-search:codesearch-soy",
                "plugin.page.codesearch.repositorySettingsPage",
                data);
        } catch (Exception e) {
            log.error("Error rendering Soy template", e);
        }
    }

    @Override
    protected void doGet (HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        if (!verifyLoggedIn(req, resp)) {
            return;
        }
        Repository repository = getRepository(req);
        if (repository == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Repo not found.");
            return;
        }
        if (verifyRepoAdmin(req, resp, repository)) {
            renderPage(req, resp, repository, settingsManager.getRepositorySettings(repository),
                Collections.emptyList());
        }
    }

    @Override
    protected void doPost (HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        if (!verifyLoggedIn(req, resp)) {
            return;
        }
        Repository repository = getRepository(req);
        if (repository == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Repo not found.");
            return;
        }
        if (!verifyRepoAdmin(req, resp, repository)) {
            return;
        }

        // Parse arguments
        ArrayList<String> errors = new ArrayList<String>();
        String refRegex = req.getParameter("refRegex");
        try {
            Pattern.compile(refRegex);
        } catch (Exception e) {
            errors.add("Invalid regex: \"" + refRegex + "\"");
        }

        // Update settings object iff no parse errors
        RepositorySettings settings;
        if (errors.isEmpty()) {
            settings = settingsManager.setRepositorySettings(repository, refRegex);
        } else {
            settings = settingsManager.getRepositorySettings(repository);
        }

        renderPage(req, resp, repository, settings, errors);
    }

    private Repository getRepository (HttpServletRequest req) {
        String uri = req.getRequestURI();
        String[] uriParts = uri.split("/");
        if (uriParts.length < 2) {
            return null;
        }
        return repositoryService.findBySlug(
            uriParts[uriParts.length - 2], uriParts[uriParts.length - 1]);
    }

}
