package com.palantir.stash.codesearch.servlet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.servlet.*;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import com.atlassian.soy.renderer.SoyTemplateRenderer;
import com.atlassian.stash.user.StashAuthenticationContext;
import com.atlassian.stash.user.StashUser;
import com.google.common.collect.ImmutableMap;

public class SearchServlet extends HttpServlet {

    private static final Logger log = LoggerFactory.getLogger(SearchServlet.class);

    private final SoyTemplateRenderer soyTemplateRenderer;

    private final StashAuthenticationContext authenticationContext;

    public SearchServlet (
            SoyTemplateRenderer soyTemplateRenderer,
            StashAuthenticationContext authenticationContext) {
        this.soyTemplateRenderer = soyTemplateRenderer;
        this.authenticationContext = authenticationContext;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {

        StashUser user = authenticationContext.getCurrentUser();
        if (user == null) {
            try {
                resp.sendRedirect("/stash/login");
            } catch (Exception e) {
                log.error("Unable to redirect unauthenticated user to login page", e);
            }
        }

        boolean doSearch = true;

        // Extract parameters
        ImmutableMap.Builder<String, String> paramBuilder = new ImmutableMap.Builder<String, String>();

        String searchString = req.getParameter("search-string");
        if (searchString == null) {
            doSearch = false;
            searchString = "";
        }
        paramBuilder.put("search-string", searchString);

        ImmutableMap<String, String> params = paramBuilder.build();

        resp.setContentType("text/html");
        try {
            ImmutableMap<String, Object> data = new ImmutableMap.Builder<String, Object>()
                .put("prevParams", params)
                .put("hasResults", doSearch)
                .build();
            soyTemplateRenderer.render(resp.getWriter(), "com.palantir.stash.stash-code-search:codesearch-soy", "plugin.codesearch.searchPage", data);
        } catch (Exception e) {
            log.error("Error rendering Soy template", e);
        }
    }

}
