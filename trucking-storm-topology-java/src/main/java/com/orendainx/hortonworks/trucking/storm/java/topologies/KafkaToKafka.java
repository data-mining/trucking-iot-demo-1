package com.orendainx.hortonworks.trucking.storm.java.topologies;

import com.orendainx.hortonworks.trucking.storm.java.bolts.CSVStringToObjectBolt;
import com.orendainx.hortonworks.trucking.storm.java.bolts.DataWindowingBolt;
import com.orendainx.hortonworks.trucking.storm.java.bolts.ObjectToCSVStringBolt;
import com.orendainx.hortonworks.trucking.storm.java.bolts.TruckAndTrafficJoinBolt;
import com.typesafe.config.ConfigFactory;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.storm.Config;
import org.apache.storm.StormSubmitter;
import org.apache.storm.generated.AlreadyAliveException;
import org.apache.storm.generated.AuthorizationException;
import org.apache.storm.generated.InvalidTopologyException;
import org.apache.storm.generated.StormTopology;
import org.apache.storm.kafka.bolt.KafkaBolt;
import org.apache.storm.kafka.bolt.mapper.FieldNameBasedTupleToKafkaMapper;
import org.apache.storm.kafka.bolt.selector.DefaultTopicSelector;
import org.apache.storm.kafka.spout.KafkaSpout;
import org.apache.storm.kafka.spout.KafkaSpoutConfig;
import org.apache.storm.topology.TopologyBuilder;
import org.apache.storm.topology.base.BaseWindowedBolt;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Values;

import java.util.Properties;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Create a topology with the following components.
 *
 * Spouts:
 *   - KafkaSpout (for injesting EnrichedTruckData)
 *   - KafkaSpout (for injesting TrafficData)
 * Bolt:
 *   - CSVStringToObjectBolt (for creating Java objects from strings)
 *   - TruckAndTrafficJoinBolt (for joining two datatypes into one)
 *   - DataWindowingBolt (for reducing lists of tuples into models for machine learning)
 *   - ObjectToCSVStringBolt (for serializing Java objects into strings)
 *   - KafkaBolt (for pushing strings into Kafka topics)
 *
 * @author Edgar Orendain <edgar@orendainx.com>
 */
public class KafkaToKafka {

  private static com.typesafe.config.Config config = ConfigFactory.load();

  public static void main(String[] args) {
    // Create a Storm config object and set some configurations
    Config stormConfig = new Config();
    stormConfig.setDebug(config.getBoolean(Config.TOPOLOGY_DEBUG));
    stormConfig.setMessageTimeoutSecs(config.getInt(Config.TOPOLOGY_MESSAGE_TIMEOUT_SECS));
    stormConfig.setNumWorkers(config.getInt(Config.TOPOLOGY_WORKERS));

    // Build a KafkaToKafka topology
    StormTopology topology = KafkaToKafka.buildTopology();

    try {
      // Submit the topology to the cluster with the specified name and storm configuration
      StormSubmitter.submitTopologyWithProgressBar("KafkaToKafka", stormConfig, topology);
    } catch (AlreadyAliveException|InvalidTopologyException|AuthorizationException e) {
      e.printStackTrace();
    }
  }

  private static StormTopology buildTopology() {

    // A builder to perform the construction of the topology.
    final TopologyBuilder builder = new TopologyBuilder();

    // List of Kafka bootstrap servers
    String bootstrapServers = config.getString("kafka.bootstrap-servers");

    // An identifier for the consumer group the configured KafkaSpouts belong to.
    String groupId = config.getString("kafka.group-id");



    /*
     * Build a Kafka spout for ingesting enriched truck data
     */

    // Name of the Kafka topic to connect to
    String truckTopic = config.getString("kafka.truck-data.topic");

    /*
     * Create a Kafka spout configuration object
     *
     * setRecordTranslator allows you to modify how the spout converts a Kafka Consumer Record into a Tuple.
     * setFirstPollOffsetStrategy as EARLIEST means that the kafka spout polls records starting in the first offset of the partition, regardless of previous commits.
     * setGroupId lets you set the id of the kafka consumer group property
     */
    KafkaSpoutConfig<String, String> truckSpoutConfig = KafkaSpoutConfig.builder(bootstrapServers, truckTopic)
        .setRecordTranslator(r -> new Values("EnrichedTruckData", r.value()), new Fields("dataType", "data"))
        .setFirstPollOffsetStrategy(KafkaSpoutConfig.FirstPollOffsetStrategy.EARLIEST)
        .setGroupId(groupId)
        .build();

    // Create a spout with the specified configuration, and place it in the topology blueprint
    builder.setSpout("enrichedTruckData", new KafkaSpout<>(truckSpoutConfig), 1);



    /*
     * Build a second Kafka spout for ingesting traffic data
     */

    // Name of the Kafka topic to connect to
    String trafficTopic = config.getString("kafka.traffic-data.topic");

    KafkaSpoutConfig<String, String> trafficSpoutConfig = KafkaSpoutConfig.builder(bootstrapServers, trafficTopic)
        .setRecordTranslator(r -> new Values("TrafficData", r.value()), new Fields("dataType", "data"))
        .setFirstPollOffsetStrategy(KafkaSpoutConfig.FirstPollOffsetStrategy.EARLIEST)
        .setGroupId(groupId)
        .build();

    builder.setSpout("trafficData", new KafkaSpout<>(trafficSpoutConfig), 1);



    /*
     * Build a bolt for creating Java objects from the ingested strings
     */

    // Our custom bolt, CSVStringToObjectBolt, is given the bolt id of "unpackagedData".  Storm is told to assign only
    // a single task for this bolt (i.e. create only 1 instance of this bolt in the cluster).
    // ShuffleGrouping shuffles data flowing in from the specified spouts evenly across all instances of the newly
    // created bolt (which is only 1 in this example)
    builder.setBolt("unpackagedData", new CSVStringToObjectBolt(), 1)
        .shuffleGrouping("enrichedTruckData")
        .shuffleGrouping("trafficData");



    /*
     * Build a windowed bolt for joining two types of Tuples into one
     */

    // The length of the window, in milliseconds.
    int windowDuration = config.getInt(Config.TOPOLOGY_BOLTS_WINDOW_LENGTH_DURATION_MS);

    // Create a tumbling windowed bolt using our custom TruckAndTrafficJoinBolt, which houses the logic for how to
    // merge the different Tuples.
    //
    // A tumbling window with a duration means the stream of incoming Tuples are partitioned based on the time
    // they were processed (think of a traffic light, allowing all vehicles to pass but only the ones that get there
    // by the time the light turns red).  All tuples that made it within the window are then processed all at once
    // in the TruckAndTrafficJoinBolt.
    BaseWindowedBolt joinBolt = new TruckAndTrafficJoinBolt().withTumblingWindow(new BaseWindowedBolt.Duration(windowDuration, MILLISECONDS));

    // GlobalGrouping suggests that all data from the specified component (unpackagedData) goes to a single one of the
    // bolt's tasks.
    builder.setBolt("joinedData", joinBolt, 1).globalGrouping("unpackagedData");



    /*
     * Build a bolt to generate driver stats from the Tuples in the stream.
     */

    // The size of the window, in number of Tuples.
    int intervalCount = config.getInt(Config.TOPOLOGY_BOLTS_SLIDING_INTERVAL_COUNT);

    // Creates a sliding windowed bolt using our custom DataWindowindBolt, which is responsible for reducing a list
    // of recent Tuples(data) for a particular driver into a single datatype.  This data is used for machine learning.
    //
    // This sliding windowed bolt with a tuple count as a length means we always process the last 'N' tuples in the
    // specified bolt.  The window slides over by one, dropping the oldest, each time a new tuple is processed.
    BaseWindowedBolt statsBolt = new DataWindowingBolt().withWindow(new BaseWindowedBolt.Count(intervalCount));

    // Build a bolt and then place in the topology blueprint connected to the "joinedData" stream.
    //
    // Create 5 tasks for this bolt, to ease the load for any single instance of this bolt.
    // FieldsGrouping partitions the stream of tuples by the fields specified.  Tuples with the same driverId will
    // always go to the same task.  Tuples with different driverIds may go to different tasks.
    builder.setBolt("windowedDriverStats", statsBolt, 5).fieldsGrouping("joinedData", new Fields("driverId"));



    /*
     * Build bolts to serialize data into a CSV string.
     */

    // This bolt ingests tuples from the "joinedData" bolt, which streams instances of EnrichedTruckAndTrafficData
    builder.setBolt("serializedJoinedData", new ObjectToCSVStringBolt()).shuffleGrouping("joinedData");

    // This bolt ingests tuples from the "joinedData" bolt, which streams instances of WindowedDriverStats
    builder.setBolt("serializedDriverStats", new ObjectToCSVStringBolt()).shuffleGrouping("windowedDriverStats");



    /*
     * Build KafkaBolts to stream tuples into a Kafka topic
     */

    // Define properties to pass along to the two KafkaBolts
    Properties kafkaBoltProps = new Properties();
    kafkaBoltProps.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.getString("kafka.bootstrap-servers"));
    kafkaBoltProps.setProperty(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, config.getString("kafka.key-serializer"));
    kafkaBoltProps.setProperty(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, config.getString("kafka.value-serializer"));

    // withTopicSelector() specifies the Kafka topic to drop entries into
    //
    // withTupleToKafkaMapper() is passed an instance of FieldNameBasedTupleToKafkaMapper, which tells the bolt
    // which fields of a Tuple the data to pass in is stored as.
    //
    // withProducerProperties() takes in properties to set itself up with
    KafkaBolt truckingKafkaBolt = new KafkaBolt<String, String>()
        .withTopicSelector(new DefaultTopicSelector(config.getString("kafka.joined-data.topic")))
        .withTupleToKafkaMapper(new FieldNameBasedTupleToKafkaMapper<>("key", "data"))
        .withProducerProperties(kafkaBoltProps);

    builder.setBolt("joinedDataToKafka", truckingKafkaBolt, 1).shuffleGrouping("serializedJoinedData");

    // Build a KafkaBolt
    KafkaBolt statsKafkaBolt = new KafkaBolt<String, String>()
        .withTopicSelector(new DefaultTopicSelector(config.getString("kafka.driver-stats.topic")))
        .withTupleToKafkaMapper(new FieldNameBasedTupleToKafkaMapper<>("key", "data"))
        .withProducerProperties(kafkaBoltProps);

    builder.setBolt("driverStatsToKafka", statsKafkaBolt, 1).shuffleGrouping("serializedDriverStats");




    // Now that the entire topology blueprint has been built, we create an actual topology from it
    return builder.createTopology();
  }
}
