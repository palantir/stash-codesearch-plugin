/**
 * Class that automatically queues and submits ElasticSearch requests via BulkRequests. This class
 * is _not_ thread-safe.
 */

package com.palantir.stash.codesearch.elasticsearch;

import org.elasticsearch.client.Client;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.delete.DeleteRequestBuilder;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.action.update.UpdateRequestBuilder;

public class RequestBuffer {

    private final Client client;

    private final int size;

    private BulkRequestBuilder bulkRequest;

    public RequestBuffer (Client client) {
        this(client, 500);
    }

    public RequestBuffer (Client client, int size) {
        this.client = client;
        this.size = size;
        this.bulkRequest = client.prepareBulk();
    }

    public void add (DeleteRequestBuilder req) {
        bulkRequest.add(req);
        flushIfNeeded();
    }

    public void add (DeleteRequest req) {
        bulkRequest.add(req);
        flushIfNeeded();
    }

    public void add (IndexRequestBuilder req) {
        bulkRequest.add(req);
        flushIfNeeded();
    }

    public void add (IndexRequest req) {
        bulkRequest.add(req);
        flushIfNeeded();
    }

    public void add (UpdateRequestBuilder req) {
        bulkRequest.add(req);
        flushIfNeeded();
    }

    public void add (UpdateRequest req) {
        bulkRequest.add(req);
        flushIfNeeded();
    }

    public BulkResponse flushIfNeeded () {
        if (bulkRequest.numberOfActions() >= size) {
            return flush();
        }
        return null;
    }

    public BulkResponse flush () {
        BulkResponse resp = null;
        if (bulkRequest.numberOfActions() > 0) {
            resp = bulkRequest.get();
            bulkRequest = client.prepareBulk();
        }
        return resp;
    }

}
