/**
 * Global settings for stash codesearch.
 */

package com.palantir.stash.codesearch.admin;

import net.java.ao.Entity;
import net.java.ao.Preload;
import net.java.ao.schema.Default;
import net.java.ao.schema.NotNull;
import net.java.ao.schema.Table;
import net.java.ao.schema.Unique;

@Table("SCSGlobalSettings")
@Preload
public interface GlobalSettings extends Entity {

    @NotNull
    @Unique
    public String getGlobalSettingsId ();
    public void setGlobalSettingsId (String value);

    public static final boolean INDEXING_ENABLED_DEFAULT = false;
    @NotNull
    @Default(INDEXING_ENABLED_DEFAULT + "")
    public boolean getIndexingEnabled ();
    public void setIndexingEnabled (boolean value);

    // Maximum file size to index (in bytes)
    public static final int MAX_FILE_SIZE_DEFAULT = 256 * 1024;
    public static final int MAX_FILE_SIZE_LB = 1024;
    public static final int MAX_FILE_SIZE_UB = 16 * 1024 * 1024;
    @NotNull
    @Default(MAX_FILE_SIZE_DEFAULT + "")
    public int getMaxFileSize ();
    public void setMaxFileSize (int value);

    // Elasticsearch query timeout (in milliseconds)
    public static final int SEARCH_TIMEOUT_DEFAULT = 10000;
    public static final int SEARCH_TIMEOUT_LB = 1000;
    public static final int SEARCH_TIMEOUT_UB = 300000;
    @NotNull
    @Default(SEARCH_TIMEOUT_DEFAULT + "")
    public int getSearchTimeout ();
    public void setSearchTimeout (int value);

    // File extensions to exclude from syntax highlighting
    public static final String NO_HIGHLIGHT_EXTENSIONS_DEFAULT = "txt,log";
    @NotNull
    @Default(NO_HIGHLIGHT_EXTENSIONS_DEFAULT)
    public String getNoHighlightExtensions ();
    public void setNoHighlightExtensions (String value);

    // Maximum number of lines to show in a file preview
    public static final int MAX_PREVIEW_LINES_DEFAULT = 10;
    public static final int MAX_PREVIEW_LINES_LB = 1;
    public static final int MAX_PREVIEW_LINES_UB = 10000;
    @NotNull
    @Default(MAX_PREVIEW_LINES_DEFAULT + "")
    public int getMaxPreviewLines ();
    public void setMaxPreviewLines (int value);

    // Maximum number of lines to show from a file that matches a query
    public static final int MAX_MATCH_LINES_DEFAULT = 50;
    public static final int MAX_MATCH_LINES_LB = 1;
    public static final int MAX_MATCH_LINES_UB = 10000;
    @NotNull
    @Default(MAX_MATCH_LINES_DEFAULT + "")
    public int getMaxMatchLines ();
    public void setMaxMatchLines (int value);

    // Maximum number of fragments to request per document from ElasticSearch's highlight API
    public static final int MAX_FRAGMENTS_DEFAULT = 50;
    public static final int MAX_FRAGMENTS_LB = 1;
    public static final int MAX_FRAGMENTS_UB = 10000;
    @NotNull
    @Default(MAX_FRAGMENTS_DEFAULT + "")
    public int getMaxFragments ();
    public void setMaxFragments (int value);

    // Number of results to show per page
    public static final int PAGE_SIZE_DEFAULT = 50;
    public static final int PAGE_SIZE_LB = 5;
    public static final int PAGE_SIZE_UB = 1000;
    @NotNull
    @Default(PAGE_SIZE_DEFAULT + "")
    public int getPageSize ();
    public void setPageSize (int value);

    // Boost factor for matching commit hashes (relative to source code matches)
    public static final double COMMIT_HASH_BOOST_DEFAULT = 100.0;
    public static final double COMMIT_HASH_BOOST_LB = 0.01;
    public static final double COMMIT_HASH_BOOST_UB = 100.0;
    @NotNull
    @Default(COMMIT_HASH_BOOST_DEFAULT + "")
    public double getCommitHashBoost ();
    public void setCommitHashBoost (double value);

    // Boost factor for matching commit subjects
    public static final double COMMIT_SUBJECT_BOOST_DEFAULT = 4.0;
    public static final double COMMIT_SUBJECT_BOOST_LB = 0.01;
    public static final double COMMIT_SUBJECT_BOOST_UB = 100.0;
    @NotNull
    @Default(COMMIT_SUBJECT_BOOST_DEFAULT + "")
    public double getCommitSubjectBoost ();
    public void setCommitSubjectBoost (double value);

    // Boost factor for matching commit message bodies
    public static final double COMMIT_BODY_BOOST_DEFAULT = 2.0;
    public static final double COMMIT_BODY_BOOST_LB = 0.01;
    public static final double COMMIT_BODY_BOOST_UB = 100.0;
    @NotNull
    @Default(COMMIT_BODY_BOOST_DEFAULT + "")
    public double getCommitBodyBoost ();
    public void setCommitBodyBoost (double value);

    // Boost factor for matching file names
    public static final double FILE_NAME_BOOST_DEFAULT = 5.0;
    public static final double FILE_NAME_BOOST_LB = 0.01;
    public static final double FILE_NAME_BOOST_UB = 100.0;
    @NotNull
    @Default(FILE_NAME_BOOST_DEFAULT + "")
    public double getFileNameBoost ();
    public void setFileNameBoost (double value);

}
