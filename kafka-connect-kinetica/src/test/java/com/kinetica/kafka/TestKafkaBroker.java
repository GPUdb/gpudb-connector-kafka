package com.kinetica.kafka;

import com.gpudb.BulkInserter;
import com.gpudb.GPUdb;
import com.gpudb.GPUdbBase;
import com.gpudb.RecordObject;
import com.gpudb.Type;
import com.gpudb.protocol.CreateTableRequest;

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.sink.SinkTask;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
/*
 * Tests Kinetica SinkTask and SourceTask processing. Kafka side is emulated, 
 * Kinetica connection and table-related config options should be configured 
 * in config/quickstart-kinetica-sink.properties and config/quickstart-kinetica-source.properties.
 * Autogenerated messages are read from and written into different Kinetica tables 
 */
public class TestKafkaBroker {
	
    private GPUdb gpudb;

    private static final String TABLES = "tableA";
    private static final String TOPICS = "topicY";
    private static final String COLLECTION_NAME = "TEST";

    private final static Logger LOG = LoggerFactory.getLogger(TestConnector.class);
    private static final long NUM_RECS = 10;

    private Map<String, String> sourceConfig = null;
    private Map<String, String> sinkConfig = null;

    @Before
    public void setup() throws Exception {
        this.sourceConfig = getConfig("config/quickstart-kinetica-source.properties");
        this.sourceConfig.put(KineticaSourceConnectorConfig.PARAM_TABLE_NAMES, COLLECTION_NAME + "." + TABLES);
        this.sinkConfig = getConfig("config/quickstart-kinetica-sink.properties");
        this.sinkConfig.put(SinkTask.TOPICS_CONFIG, TOPICS);
        this.sinkConfig.put(KineticaSinkConnectorConfig.DEPRECATED_PARAM_COLLECTION, COLLECTION_NAME);
    }

    @After
    public void cleanup() throws Exception {
    	//TestUtils.tableCleanUp(this.gpudb, "TEST.outtableA");
    	//TestUtils.tableCleanUp(this.gpudb, "TEST.tableA");
    	this.gpudb = null;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static Map<String, String> getConfig(String fileName) throws Exception {
        Properties props = new Properties();
        try (Reader propsReader = new FileReader(fileName)) {
            LOG.info("Loading properties: {}", fileName);
            props.load(propsReader);
        }

        return new HashMap<String, String>((Map) props);
    }

    @Test
    public void testConnector() throws Exception {

        try {
            createSourceTable();

            KineticaSourceTask sourceTask = startSourceConnector(this.sourceConfig);

            // wait for ZMQ to start
            Thread.sleep(10000);

            insertTableRecs(this.sourceConfig, NUM_RECS);

            // wait for ZMQ to pick up the rows
            Thread.sleep(10000);

            // get the results
            List<SourceRecord> sourceRecords = sourceTask.poll();

            LOG.info("Stopping source task...");
            sourceTask.stop();

            ArrayList<SinkRecord> sinkRecords = convertSourceToSink(sourceRecords);

            LOG.info("Sending records to sink...");
            KineticaSinkTask sinkTask = startSinkConnector(this.sinkConfig);
            sinkTask.put(sinkRecords);
            sinkTask.flush(null);
            sinkTask.stop();

            LOG.info("Test Complete!");
        } catch (Exception ex) {
            LOG.error("Test failed", ex);
            throw ex;
        }
    }
    
    /**
     * Helper function
     * cleans up existing Kinetica test tables for a clean test run
     */
    public void createSourceTable() throws Exception {

        try {
            String gpudbURL = this.sourceConfig.get(KineticaSourceConnectorConfig.PARAM_URL);
            String tableName = sourceConfig.get(KineticaSourceConnectorConfig.PARAM_TABLE_NAMES);

            this.gpudb = new GPUdb(gpudbURL, new GPUdb.Options()
                    .setUsername(sourceConfig.get(KineticaSourceConnectorConfig.PARAM_USERNAME))
                    .setPassword(sourceConfig.get(KineticaSourceConnectorConfig.PARAM_PASSWORD))
                    .setTimeout(0));

            if (gpudb.hasTable(tableName, null).getTableExists()) {
                LOG.info("Dropping table: {}", tableName);
                gpudb.clearTable(tableName, null, null);
            }

            String typeId = RecordObject.createType(TweetRecord.class, gpudb);

            LOG.info("Creating table: {}", tableName);
            Map<String, String> options = GPUdbBase.options(CreateTableRequest.Options.COLLECTION_NAME,
                    COLLECTION_NAME);
            gpudb.createTable(tableName, typeId, options);

            LOG.info("Done!");
        } catch (Exception ex) {
            LOG.error("Test failed", ex);
            throw ex;
        }
    }

    /**
     * Helper function
     * A set of previously used SourceRecords is converted into SinkRecords to skip generating data from scratch
     * @param sourceList   previously used SourceRecords
     * @return a list of SinkRecords
     */
    public ArrayList<SinkRecord> convertSourceToSink(List<SourceRecord> sourceList) {
        ArrayList<SinkRecord> sincRecords = new ArrayList<>(sourceList.size());

        LOG.info("Converting source records: {}", sourceList.size());

        for (int recNum = 0; recNum < sourceList.size(); recNum++) {
            SourceRecord sourceRec = sourceList.get(recNum);

            String topic = sourceRec.topic();
            // Integer partition = sourceRec.kafkaPartition();
            Schema keySchema = sourceRec.keySchema();
            Object key = sourceRec.key();
            Schema valueSchema = sourceRec.valueSchema();
            Object value = sourceRec.value();

            SinkRecord sincRec = new SinkRecord(topic, 0, keySchema, key, valueSchema, value, recNum);
            sincRecords.add(sincRec);
        }

        return sincRecords;
    }

    /**
     * Helper function
     * Configures and starts SourceConnector with configuration files provided with the project
     * @param sourceConfig   source configuration
     * @return
     * @throws Exception
     */
    public KineticaSourceTask startSourceConnector(Map<String, String> sourceConfig) throws Exception {
        KineticaSourceConnector sourceConnector = new KineticaSourceConnector();
        sourceConnector.start(sourceConfig);

        List<Map<String, String>> taskConfigs = sourceConnector.taskConfigs(1);
        Map<String, String> taskConfig = taskConfigs.get(0);

        KineticaSourceTask task = new KineticaSourceTask();
        LOG.info("Starting source task...");
        task.start(taskConfig);

        return task;
    }

    /**
     * Helper function
     * Configures and starts SinkConnector with configuration files provided with the project
     * @param sinkConfig     sink configuration
     * @return               started KineticaSinkTask
     * @throws Exception
     */
    public static KineticaSinkTask startSinkConnector(Map<String, String> sinkConfig) throws Exception {
        KineticaSinkConnector sinkConnector = new KineticaSinkConnector();
        sinkConnector.start(sinkConfig);

        List<Map<String, String>> taskConfigs = sinkConnector.taskConfigs(1);
        Map<String, String> config = taskConfigs.get(0);

        KineticaSinkTask task = new KineticaSinkTask();
        LOG.info("Starting sink task...");
        task.start(config);

        return task;
    }

    @Test
    public void testInsertTableRecs() throws Exception {
        insertTableRecs(this.sourceConfig, NUM_RECS);
    }

    /**
     * Helper function
     * Autogenerates a given number of records and inserts into Kinetica table 
     * @param sourceConfig   source configuration
     * @param numRecs        number of records to produce
     * @throws Exception
     */
    public void insertTableRecs(Map<String, String> sourceConfig, long numRecs) throws Exception {

        String gpudbURL = sourceConfig.get(KineticaSourceConnectorConfig.PARAM_URL);
        String tableName = sourceConfig.get(KineticaSourceConnectorConfig.PARAM_TABLE_NAMES);
        GPUdb gpudb = new GPUdb(gpudbURL, new GPUdb.Options()
                .setUsername(sourceConfig.get(KineticaSourceConnectorConfig.PARAM_USERNAME))
                .setPassword(sourceConfig.get(KineticaSourceConnectorConfig.PARAM_PASSWORD))
                .setTimeout(0));

        LOG.info("Generating {} records ", numRecs);
        ArrayList<TweetRecord> records = new ArrayList<>();
        for (int recNum = 0; recNum < numRecs; recNum++) {
            records.add(TweetRecord.generateRandomRecord());
        }

        LOG.info("Inserting records.");
        Type recordtype = RecordObject.getType(TweetRecord.class);
        BulkInserter<TweetRecord> bulkInserter = new BulkInserter<>(gpudb, tableName, recordtype, 100, null);
        bulkInserter.insert(records);
        bulkInserter.flush();

        if (bulkInserter.getCountInserted() != numRecs) {
            throw new Exception(String.format("Added %d records but only %d were inserted.", records.size(),
                    bulkInserter.getCountInserted()));
        }

        records.clear();
    }

    
}
