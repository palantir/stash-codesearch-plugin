/**
 * Provides the elasticsearch Node and Client objects used by all other classes.
 */

package com.palantir.stash.codesearch.elasticsearch;

import org.elasticsearch.client.Client;

public interface ElasticSearch {

    public static final String ES_UPDATEALIAS = "scs-update";

    public static final String ES_SEARCHALIAS = "scs-search";

    Client getClient();

}
