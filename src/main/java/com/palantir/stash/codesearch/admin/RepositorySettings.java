/**
 * Repository settings for stash codesearch.
 */

package com.palantir.stash.codesearch.admin;

import net.java.ao.Entity;
import net.java.ao.Preload;
import net.java.ao.schema.Default;
import net.java.ao.schema.NotNull;
import net.java.ao.schema.Table;
import net.java.ao.schema.Unique;

@Table("SCSRepoSettings")
@Preload
public interface RepositorySettings extends Entity {

    @NotNull
    @Unique
    public String getRepositoryId ();
    public void setRepositoryId (String value);

    // Regex for selecting refs to index
    public static final String REF_REGEX_DEFAULT = "refs/heads/(master|develop)";
    @NotNull
    @Default(REF_REGEX_DEFAULT)
    public String getRefRegex ();
    public void setRefRegex (String value);

}
