package com.gpudb.kafka;

import com.gpudb.Avro;
import com.gpudb.GPUdb;
import com.gpudb.GPUdbException;
import com.gpudb.GenericRecord;
import com.gpudb.Type;
import com.gpudb.Type.Column;
import com.gpudb.protocol.CreateTableMonitorResponse;
import com.gpudb.protocol.ShowTableRequest;
import com.gpudb.protocol.ShowTableResponse;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import org.apache.kafka.common.utils.AppInfoParser;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.source.SourceTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeromq.ZFrame;
import org.zeromq.ZMQ;
import org.zeromq.ZMsg;

public class GPUdbSourceTask extends SourceTask {
    private static final Logger LOG = LoggerFactory.getLogger(GPUdbSourceTask.class);

    private LinkedBlockingQueue<SourceRecord> queue;
    private Thread monitorThread;

    @Override
    public void start(final Map<String, String> props) {
        final URL url;
        final String tableName = props.get(GPUdbSourceConnector.TABLE_NAME_CONFIG);
        final String topic = props.get(GPUdbSourceConnector.TOPIC_CONFIG);

        try {
            url = new URL(props.get(GPUdbSourceConnector.URL_CONFIG));
        } catch (MalformedURLException ex) {
            throw new IllegalArgumentException("Invalid URL (" + props.get(GPUdbSourceConnector.URL_CONFIG) + ").");
        }

        final GPUdb gpudb;
        final String zmqUrl;

        try {
            gpudb = new GPUdb(url, new GPUdb.Options()
                    .setUsername(props.get(GPUdbSourceConnector.USERNAME_CONFIG))
                    .setPassword(props.get(GPUdbSourceConnector.PASSWORD_CONFIG))
                    .setTimeout(Integer.parseInt(props.get(GPUdbSourceConnector.TIMEOUT_CONFIG))));

            // Get table info from /show/table.

            ShowTableResponse tableInfo = gpudb.showTable(tableName, GPUdb.options(ShowTableRequest.Options.SHOW_CHILDREN, ShowTableRequest.Options.FALSE));

            // If the specified table is a collection, fail.

            if (tableInfo.getTableDescriptions().get(0).contains(ShowTableResponse.TableDescriptions.COLLECTION)) {
                throw new IllegalArgumentException("Cannot create connector for collection " + tableName + ".");
            }

            // Get the table monitor URL from /show/system/properties. If table
            // monitor support is not enabled or the port is invalid, fail.

            String zmqPortString = gpudb.showSystemProperties(GPUdb.options("properties", "conf.set_monitor_port")).getPropertyMap().get("conf.set_monitor_port");

            if (zmqPortString == null || zmqPortString.equals("-1")) {
                throw new RuntimeException("Table monitor not supported.");
            }

            int zmqPort;

            try {
                zmqPort = Integer.parseInt(zmqPortString);
            } catch (Exception ex) {
                throw new RuntimeException("Invalid table monitor port (" + zmqPortString + ").");
            }

            if (zmqPort < 1 || zmqPort > 65535) {
                throw new RuntimeException("Invalid table monitor port (" + zmqPortString + ").");
            }

            zmqUrl = "tcp://" + url.getHost() + ":" + zmqPort;
        } catch (GPUdbException ex) {
            throw new RuntimeException(ex.getMessage(), ex);
        }

        queue = new LinkedBlockingQueue<>();

        // Create a thread that will manage the table monitor and convert
        // records from the monitor into source records and put them into the
        // source record queue.

        monitorThread = new Thread() {
            @Override
            public void run() {
                Type type;
                String topicId;

                try {
                    // Create the table monitor.

                    CreateTableMonitorResponse response = gpudb.createTableMonitor(tableName, null);
                    type = new Type(response.getTypeSchema());
                    topicId = response.getTopicId();
                } catch (GPUdbException ex) {
                    LOG.error("Could not create table monitor for " + tableName + " at " + url + ".", ex);
                    return;
                }

                // Create a Kafka schema from the table type.

                SchemaBuilder builder = SchemaBuilder.struct().name(tableName).version(1);

                for (Column column : type.getColumns())
                {
                    Schema fieldSchema;

                    if (column.getType() == ByteBuffer.class) {
                        fieldSchema = Schema.BYTES_SCHEMA;
                    } else if (column.getType() == Double.class) {
                        fieldSchema = Schema.FLOAT64_SCHEMA;
                    } else if (column.getType() == Float.class) {
                        fieldSchema = Schema.FLOAT32_SCHEMA;
                    } else if (column.getType() == Integer.class) {
                        fieldSchema = Schema.INT32_SCHEMA;
                    } else if (column.getType() == Long.class) {
                        fieldSchema = Schema.INT64_SCHEMA;
                    } else if (column.getType() == String.class) {
                        fieldSchema = Schema.STRING_SCHEMA;
                    } else {
                        LOG.error("Unknown column type " + column.getType().getName() + ".");
                        return;
                    }

                    builder = builder.field(column.getName(), fieldSchema);
                }

                Schema schema = builder.build();

                // Subscribe to the ZMQ topic for the table monitor.

                try (ZMQ.Context zmqContext = ZMQ.context(1); ZMQ.Socket subscriber = zmqContext.socket(ZMQ.SUB)) {
                    subscriber.connect(zmqUrl);
                    subscriber.subscribe(topicId.getBytes());
                    subscriber.setReceiveTimeOut(1000);

                    // Loop until the thread is interrupted when the task is
                    // stopped.

                    long recordNumber = 0;

                    while (!Thread.currentThread().isInterrupted()) {
                        // Check for a ZMQ message; if none was received within
                        // the timeout window continue waiting.

                        ZMsg message = ZMsg.recvMsg(subscriber);

                        if (message == null) {
                            continue;
                        }

                        boolean skip = true;

                        for (ZFrame frame : message) {
                            // Skip the first frame (which just contains the
                            // topic ID).

                            if (skip) {
                                skip = false;
                                continue;
                            }

                            // Create a source record for each record and put it
                            // into the source record queue.

                            GenericRecord record = Avro.decode(type, ByteBuffer.wrap(frame.getData()));
                            Struct struct = new Struct(schema);

                            for (Column column : type.getColumns()) {
                                Object value = record.get(column.getName());

                                if (value instanceof ByteBuffer) {
                                    value = ((ByteBuffer)value).array();
                                }

                                struct.put(column.getName(), value);
                            }

                            queue.add(new SourceRecord(
                                    Collections.singletonMap("table", tableName),
                                    Collections.singletonMap("record", recordNumber),
                                    props.get(GPUdbSourceConnector.TOPIC_CONFIG),
                                    Schema.INT64_SCHEMA,
                                    recordNumber,
                                    schema,
                                    struct
                            ));

                            recordNumber++;
                        }
                    }
                } catch (Exception ex) {
                    LOG.error("Could not access table monitor for " + tableName + " at " + zmqUrl + ".", ex);
                }

                // The task has been stopped (or something failed) so clear the
                // table monitor.

                try {
                    gpudb.clearTableMonitor(topicId, null);
                } catch (GPUdbException ex) {
                    LOG.error("Could not clear table monitor for " + tableName + " at " + url + ".", ex);
                }
            }
        };

        monitorThread.start();
    }

    @Override
    public List<SourceRecord> poll() throws InterruptedException {
        // Copy any source records in the source record queue to the result;
        // block to wait for source records if none are present.

        List<SourceRecord> result = new ArrayList<>();

        if (queue.isEmpty())
        {
            result.add(queue.take());
        }

        queue.drainTo(result, 1000);

        LOG.info("Sourced " + result.size() + " records");

        return result;
    }

    @Override
    public void stop() {
        // Interrupt the monitor thread and wait for it to terminate.

        monitorThread.interrupt();

        try {
            monitorThread.join();
        } catch (Exception ex) {
        }
    }

    @Override
    public String version() {
        return AppInfoParser.getVersion();
    }
}