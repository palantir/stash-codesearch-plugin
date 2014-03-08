/**
 * Class that searches a source file for a string, and returns a set of matching lines
 * (or a preview if not found.)
 */

package com.palantir.stash.codesearch.search;

import com.google.common.collect.ImmutableSet;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.*;
import org.elasticsearch.common.text.Text;
import org.elasticsearch.search.highlight.*;

class SourceSearch {

    private static final Pattern FRAGMENT_REGEX = Pattern.compile("\u0001(.*)\u0001");

    private final boolean preview;

    private final String[] lines;

    private final int[] lineNums;

    private final int excess;

    private SourceSearch (boolean preview, String[] lines, int[] lineNums, int excess) {
        this.preview = preview;
        this.lines = lines;
        this.lineNums = lineNums;
        this.excess = excess;
    }

    public boolean isPreview () {
        return preview;
    }

    public String[] getLines () {
        return lines;
    }

    public int[] getLineNums () {
        return lineNums;
    }

    public int getExcess () {
        return excess;
    }

    public String getJoinedLines () {
        if (lines.length < 1) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            builder.append(line);
            builder.append('\n');
        }
        return builder.toString();
    }

    public String getJoinedLineNums () {
        if (lineNums.length < 1) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        int maxWidth = Integer.toString(lineNums[lineNums.length - 1]).length();
        for (int lineNum : lineNums) {
            String lineNumStr = lineNum < 0 ? "" : Integer.toString(lineNum);
            for (int i = lineNumStr.length(); i < maxWidth; ++i) {
                builder.append(' ');
            }
            builder.append(lineNumStr);
            builder.append('\n');
        }
        return builder.toString();
    }

    private static boolean lineMatches (String line, Set<String> matches) {
        for (String match : matches) {
            if (line.contains(match)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Performs a SourceSearch for all lines within contextLines distance of a matching line.
     */
    public static SourceSearch search (
            String source,  // source string to search
            HighlightField highlightField,  // ES highlighted fragments
            int contextLines,  // number of surrounding lines to show for each match
            int previewLines,  // number of lines to show for file previews (files with no matches)
            int lineLimit) {  // maximum number of lines to display

        // Find matching snippets from fragment array
        ImmutableSet.Builder<String> matchSetBuilder = new ImmutableSet.Builder<String>();
        if (highlightField != null) {
            for (Text fragment : highlightField.getFragments()) {
                Matcher m = FRAGMENT_REGEX.matcher(fragment.toString());
                while (m.find()) {
                    matchSetBuilder.add(m.group(1));
                }
            }
        }
        ImmutableSet<String> matchSet = matchSetBuilder.build();

        // Find matching lines
        String[] sourceLines = source.split("\r?\n|\r");
        boolean[] includeLine = new boolean[sourceLines.length];
        boolean[] ellipsisLine = new boolean[sourceLines.length];
        int numMatches = 0;
        for (int i = 0; i < sourceLines.length; ++i) {
            if (lineMatches(sourceLines[i], matchSet)) {
                if (numMatches > 0) {
                    int ellipsisIndex = i - contextLines - 1;
                    if (ellipsisIndex >= 0 && !includeLine[ellipsisIndex]) {
                        includeLine[ellipsisIndex] = ellipsisLine[ellipsisIndex] = true;
                        ++numMatches;
                    }
                }
                for (int j = i - contextLines; j <= i + contextLines; ++j) {
                    if (j >= 0 && j < sourceLines.length && !includeLine[j]) {
                        includeLine[j] = true;
                        ++numMatches;
                    }
                }
            }
        }

        // If no matches found, initialize preview 
        int linesToShow = Math.min(numMatches, lineLimit);
        boolean preview = numMatches == 0;
        if (preview) {
            numMatches = sourceLines.length;
            linesToShow = Math.min(numMatches, previewLines);
            if (numMatches > 0) {
                Arrays.fill(includeLine, 0, linesToShow, true);
            }
        }

        // Build line and linenum arrays
        int excess = numMatches - linesToShow;
        String[] matchingLines = new String[linesToShow];
        int[] lineNums = new int[linesToShow];
        for (int i = 0, curCount = 0; i < sourceLines.length && curCount < linesToShow; ++i) {
            if (includeLine[i]) {
                matchingLines[curCount] = ellipsisLine[i] ? "..." : sourceLines[i];
                lineNums[curCount] = ellipsisLine[i] ? -1 : i + 1;
                ++curCount;
            }
        }

        return new SourceSearch(preview, matchingLines, lineNums, excess);
    }

}
