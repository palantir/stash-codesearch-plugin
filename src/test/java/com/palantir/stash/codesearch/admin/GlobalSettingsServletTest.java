package com.palantir.stash.codesearch.admin;

import java.io.PrintWriter;
import java.net.URI;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.AdditionalMatchers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import com.atlassian.soy.renderer.SoyTemplateRenderer;
import com.atlassian.bitbucket.AuthorisationException;
import com.atlassian.bitbucket.i18n.KeyedMessage;
import com.atlassian.bitbucket.server.ApplicationPropertiesService;
import com.atlassian.bitbucket.permission.Permission;
import com.atlassian.bitbucket.permission.PermissionValidationService;
import com.atlassian.bitbucket.user.SecurityService;
import com.palantir.stash.codesearch.updater.SearchUpdater;

public class GlobalSettingsServletTest {

    private static final String SOME_URL = "https://stash.servlet.test/someurl";

    @Mock
    private ApplicationPropertiesService aps;
    @Mock
    private GlobalSettings gs;
    @Mock
    private SettingsManager sm;
    @Mock
    private HttpServletRequest req;
    @Mock
    private HttpServletResponse res;
    @Mock
    private PermissionValidationService pvs;
    @Mock
    private PrintWriter pw;
    @Mock
    private SearchUpdater su;
    @Mock
    private SecurityService ss;
    @Mock
    private SoyTemplateRenderer str;

    private GlobalSettingsServlet servlet;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        Mockito.when(aps.getLoginUri(Mockito.any(URI.class))).thenReturn(URI.create(SOME_URL));
        Mockito.when(sm.getGlobalSettings()).thenReturn(gs);
        Mockito.when(req.getRequestURL()).thenReturn(new StringBuffer(SOME_URL));
        Mockito.when(res.getWriter()).thenReturn(pw);

        servlet = new GlobalSettingsServlet(aps, sm, pvs, su, ss, str);
    }

    @Test
    public void getTestWhenNotLoggedIn() throws Exception {
        Mockito.doThrow(
            new import com.atlassian.bitbucket.AuthorisationException(new KeyedMessage("testException", "testException", "testException")))
            .when(pvs).validateAuthenticated();

        servlet.doGet(req, res);

        Mockito.verify(res).sendRedirect(Mockito.anyString());
        Mockito.verify(res, Mockito.never()).getWriter();
    }

    @Test
    public void getTestWhenNotSysAdmin() throws Exception {
        Mockito.doThrow(
            new import com.atlassian.bitbucket.AuthorisationException(new KeyedMessage("testException", "testException", "testException")))
            .when(pvs).validateForGlobal(Permission.SYS_ADMIN);

        servlet.doGet(req, res);

        Mockito.verify(res).sendError(Mockito.eq(HttpServletResponse.SC_UNAUTHORIZED), Mockito.any(String.class));
        Mockito.verify(res, Mockito.never()).getWriter();
    }

    @Test
    public void getTest() throws Exception {
        servlet.doGet(req, res);

        Mockito.verify(res).setContentType(Mockito.contains("text/html"));
        @SuppressWarnings({ "unchecked", "rawtypes" })
        ArgumentCaptor<Map<String, Object>> mapCaptor = ArgumentCaptor.forClass(
            (Class<Map<String, Object>>) (Class) Map.class);
        Mockito.verify(str).render(
            Mockito.eq(pw),
            Mockito.eq("com.palantir.stash.stash-code-search:codesearch-soy"),
            Mockito.eq("plugin.page.codesearch.globalSettingsPage"),
            mapCaptor.capture());
        Assert.assertEquals(gs, mapCaptor.getValue().get("settings"));
    }

    @Test
    public void postTest() throws Exception {
        Mockito.when(req.getParameter("indexingEnabled")).thenReturn("" + GlobalSettings.INDEXING_ENABLED_DEFAULT);
        Mockito.when(req.getParameter("maxConcurrentIndexing")).thenReturn(
            "" + GlobalSettings.MAX_CONCURRENT_INDEXING_DEFAULT);
        Mockito.when(req.getParameter("maxFileSize")).thenReturn("" + GlobalSettings.MAX_FILE_SIZE_DEFAULT);
        Mockito.when(req.getParameter("searchTimeout")).thenReturn("" + GlobalSettings.SEARCH_TIMEOUT_DEFAULT);
        Mockito.when(req.getParameter("noHighlightExtensions")).thenReturn(
            "" + GlobalSettings.NO_HIGHLIGHT_EXTENSIONS_DEFAULT);
        Mockito.when(req.getParameter("maxPreviewLines")).thenReturn("" + GlobalSettings.MAX_PREVIEW_LINES_DEFAULT);
        Mockito.when(req.getParameter("maxMatchLines")).thenReturn("" + GlobalSettings.MAX_MATCH_LINES_DEFAULT);
        Mockito.when(req.getParameter("maxFragments")).thenReturn("" + GlobalSettings.MAX_FRAGMENTS_DEFAULT);
        Mockito.when(req.getParameter("pageSize")).thenReturn("" + GlobalSettings.PAGE_SIZE_DEFAULT);
        Mockito.when(req.getParameter("commitHashBoost")).thenReturn("" + GlobalSettings.COMMIT_HASH_BOOST_DEFAULT);
        Mockito.when(req.getParameter("commitSubjectBoost")).thenReturn(
            "" + GlobalSettings.COMMIT_SUBJECT_BOOST_DEFAULT);
        Mockito.when(req.getParameter("commitBodyBoost")).thenReturn("" + GlobalSettings.COMMIT_BODY_BOOST_DEFAULT);
        Mockito.when(req.getParameter("fileNameBoost")).thenReturn("" + GlobalSettings.FILE_NAME_BOOST_DEFAULT);

        servlet.doPost(req, res);

        Mockito.verify(sm).setGlobalSettings(
            Mockito.eq(GlobalSettings.INDEXING_ENABLED_DEFAULT),
            Mockito.eq(GlobalSettings.MAX_CONCURRENT_INDEXING_DEFAULT),
            Mockito.eq(GlobalSettings.MAX_FILE_SIZE_DEFAULT),
            Mockito.eq(GlobalSettings.SEARCH_TIMEOUT_DEFAULT),
            Mockito.eq(GlobalSettings.NO_HIGHLIGHT_EXTENSIONS_DEFAULT),
            Mockito.eq(GlobalSettings.MAX_PREVIEW_LINES_DEFAULT),
            Mockito.eq(GlobalSettings.MAX_MATCH_LINES_DEFAULT),
            Mockito.eq(GlobalSettings.MAX_FRAGMENTS_DEFAULT),
            Mockito.eq(GlobalSettings.PAGE_SIZE_DEFAULT),
            AdditionalMatchers.eq(GlobalSettings.COMMIT_HASH_BOOST_DEFAULT, 1E-9),
            AdditionalMatchers.eq(GlobalSettings.COMMIT_SUBJECT_BOOST_DEFAULT, 1E-9),
            AdditionalMatchers.eq(GlobalSettings.COMMIT_BODY_BOOST_DEFAULT, 1E-9),
            AdditionalMatchers.eq(GlobalSettings.FILE_NAME_BOOST_DEFAULT, 1E-9));
        Mockito.verify(res).setContentType(Mockito.contains("text/html"));
    }

}
