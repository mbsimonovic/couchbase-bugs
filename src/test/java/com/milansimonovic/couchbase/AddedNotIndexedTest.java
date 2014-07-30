package com.milansimonovic.couchbase;

import com.couchbase.client.CouchbaseClient;
import com.couchbase.client.CouchbaseConnectionFactoryBuilder;
import com.couchbase.client.protocol.views.*;
import net.spy.memcached.PersistTo;
import net.spy.memcached.internal.OperationFuture;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;


/**
 * Demos what happens when the server starts dropping packets for a while.
 */
public class AddedNotIndexedTest {
    private static final org.apache.log4j.Logger log = org.apache.log4j.Logger.getLogger(AddedNotIndexedTest.class);

    final String NODE = "192.168.1.128";
    final String BUCKET = "default";
    static final String designDocName = "designDoc";
    static final String viewName = "by_id";
    static final String docId = "some_id";
    CouchbaseClient client;
    final String mapFunction =
            "function (doc) {\n" +
                    "    emit(doc.id, null);\n" +
                    "}\n";
    static final String docAsJson;

    static {
        try {
            final URL file = AddedNotIndexedTest.class.getClassLoader().getResource("doc_not_indexed_bug.json");
            docAsJson = new String(Files.readAllBytes(Paths.get(file.toURI())), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @BeforeClass
    public static void setup() {
        assertTrue(docAsJson.length() > 0);
        //needs to be done BEFORE CouchbaseClient is initialized!
        System.setProperty("net.spy.log.LoggerImpl", "net.spy.memcached.compat.log.Log4JLogger");
        System.setProperty("viewmode", "production");
    }

    @Before
    public void setupBefore() throws IOException {
        client = openClient();
        try {
            client.delete(docId, PersistTo.ONE);//faster than flush
        } catch (Exception e) {
            log.info("failed to delete ", e);
        }
        createView();
    }

    @Test
    public void test_addDocAndCheckIfIndexed() throws Exception {
        assertEquals(0, count());
        final OperationFuture<Boolean> f = client.add(docId, docAsJson, PersistTo.ONE);
        if (!f.get()) {
            throw new RuntimeException("failed to add: " + f);
        }
        final Object saved = client.get(docId);
        assertEquals(docAsJson, saved);
        assertEquals("saved but not indexed!?", 1, count());
        //double check: http://192.168.1.128:8092/test/_design/dev_testDesignDoc/_view/by_id?stale=false&connection_timeout=60000&limit=10

        //mapreduce_errors log says:
        //[mapreduce_errors:error,2014-07-30T22:12:51.659,ns_1@127.0.0.1:<0.25058.0>:couch_set_view_updater:load_doc:479]Bucket `default`, main group `_design/designDoc`, skipping document with ID `some_id`: too large body (1049883 bytes)
    }

    public long count() {
        final View view = client.getView(designDocName, viewName);
        Query query = new Query();
        query.setIncludeDocs(false);
//        query.setLimit(1);
        query.setStale(Stale.FALSE);
        final ViewResponse response = client.query(view, query);
        return response.getTotalRows();
    }


    private CouchbaseClient openClient() throws IOException {
        List<URI> nodesList = Arrays.asList(URI.create("http://" + NODE + ":8091/pools"));
        CouchbaseConnectionFactoryBuilder builder = new CouchbaseConnectionFactoryBuilder();
        builder.setOpTimeout(40000);
        builder.setObsTimeout(55000);
        builder.setViewTimeout(125000);

        return new CouchbaseClient(builder.buildCouchbaseConnection(nodesList, BUCKET, ""));
    }

    private void createView() {
        try {
            if (client.getDesignDoc(designDocName) != null) {
                return;
            }
        } catch (Exception e) {
        }
        try {
            client.deleteDesignDoc(designDocName);
        } catch (Exception e) {
        }
        DesignDocument designDoc = new DesignDocument(designDocName);
        designDoc.getViews().add(new ViewDesign(viewName, mapFunction));
        if (!client.createDesignDoc(designDoc)) {
            throw new RuntimeException("view creating failed");
        }
    }

    @After
    public void cleanup() {
        if (client != null) {
            client.shutdown();
        }
    }
}
