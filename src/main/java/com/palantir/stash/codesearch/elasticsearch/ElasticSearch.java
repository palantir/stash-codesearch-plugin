/**
 * Contains the elasticsearch Node and Client objects used by all other classes.
 *
 * These variables are all static since ElasticSearch best practice is apparently to
 * use 1 client per JVM. Also, we can clean up using a shutdown hook instead of relying on
 * finalize() or creating our own cleanup glue.
 *
 * Config is loaded from elasticsearch.yml in the resources path.
 */

package com.palantir.stash.codesearch.elasticsearch;

import org.elasticsearch.client.Client;
import org.elasticsearch.node.Node;
import org.elasticsearch.node.NodeBuilder;

public class ElasticSearch {

    public static final Node ES_NODE;

    public static final Client ES_CLIENT;

    public static final String ES_UPDATEALIAS = "scs-update";

    public static final String ES_SEARCHALIAS = "scs-search";

    static {
        ClassLoader currentLoader = Thread.currentThread().getContextClassLoader();
        // Hack to get names.txt from the elasticsearch CP
        Thread.currentThread().setContextClassLoader(Node.class.getClassLoader());
        ES_NODE = NodeBuilder.nodeBuilder()
            .client(true)
            .node();
        ES_CLIENT = ES_NODE.client();
        Thread.currentThread().setContextClassLoader(currentLoader);
        Runtime.getRuntime().addShutdownHook(new Thread (new Runnable () {
            @Override
            public void run () {
                ES_NODE.close();
            }
        }));
    }

}
