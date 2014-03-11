/**
 * Stores and retrieves codesearch global settings.
 */

package com.palantir.stash.codesearch.admin;

public interface GlobalSettingsManager {

    GlobalSettings getGlobalSettings ();

    GlobalSettings setGlobalSettings (
        boolean indexingEnabled,
        int maxFileSize,
        int searchTimeout,
        String noHighlightExtensions,
        int maxPreviewLines,
        int maxMatchLines,
        int maxFragments,
        int pageSize,
        double commitHashBoost,
        double commitSubjectBoost,
        double commitBodyBoost,
        double fileNameBoost);
}
