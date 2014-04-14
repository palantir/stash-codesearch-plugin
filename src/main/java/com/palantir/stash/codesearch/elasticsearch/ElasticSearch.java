/**
 * Provides the elasticsearch Node and Client objects used by all other classes.
 */

package com.palantir.stash.codesearch.elasticsearch;

import org.elasticsearch.client.Client;
import org.elasticsearch.node.Node;

public interface ElasticSearch {

    public static final String ES_UPDATEALIAS = "scs-update";

    public static final String ES_SEARCHALIAS = "scs-search";

    Node getNode ();

    Client getClient ();

}
