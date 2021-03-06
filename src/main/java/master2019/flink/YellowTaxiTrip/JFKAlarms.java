package master2019.flink.YellowTaxiTrip;

import master2019.flink.YellowTaxiTrip.events.JFKAlarmEvent;
import master2019.flink.YellowTaxiTrip.events.TripEvent;
import org.apache.flink.api.java.tuple.Tuple;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.timestamps.AscendingTimestampExtractor;
import org.apache.flink.streaming.api.functions.windowing.WindowFunction;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;

import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * In this class the JFK airport trips program has to be implemented.
 */
public class JFKAlarms {

    private static final int MIN_PASSENGER_COUNT = 2;

    public static final String JFK_ALARMS_FILE = "jfkAlarms.csv";

    public static void main(String[] args) throws Exception {

        final ParameterTool params = ParameterTool.fromArgs(args);

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);

        SingleOutputStreamOperator<String> rowsSource = env.readTextFile(params.get("input"));
        SingleOutputStreamOperator<TripEvent> mappedRows = rowsSource.map(new YellowTaxiTrip.Tokenizer());

        JFKAlarms.run(mappedRows)
                .writeAsCsv(String.format("%s/%s",params.get("output"),JFK_ALARMS_FILE),org.apache.flink.core.fs.FileSystem.WriteMode.OVERWRITE).setParallelism(1);

        env.execute("JFK Alarms");
    }

    public static SingleOutputStreamOperator<JFKAlarmEvent> run(SingleOutputStreamOperator<TripEvent> stream) {
        return stream
                .filter((TripEvent e) -> (e.get_passenger_count() >= MIN_PASSENGER_COUNT) && (e.get_RatecodeID()==2))
                .assignTimestampsAndWatermarks(new AscendingTimestampExtractor<TripEvent>() {
                    @Override
                    public long extractAscendingTimestamp(TripEvent tripEvent) {
                        return (tripEvent.get_tpep_pickup_datetime().getTime());
                    }
                })
                .keyBy(0)
                .window(TumblingEventTimeWindows.of(Time.hours(1)))
                .apply(new JFKAlarmWindow());
    }

    //private static class JFKAlarmKey extends Tuple2<Integer,Long>{}

    private static class JFKAlarmWindow implements WindowFunction<TripEvent, JFKAlarmEvent, Tuple, TimeWindow> {

        private JFKAlarmEvent jfkAlarmEvent = new JFKAlarmEvent();

        @Override
        public void apply(Tuple key, TimeWindow timeWindow,
                          Iterable<TripEvent> iterable,
                          Collector<JFKAlarmEvent> collector) throws Exception {

            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");

            Date pickupParsedDate = dateFormat.parse("9999-00-0 00:00:00");
            Timestamp pickupTimestamp = new java.sql.Timestamp(pickupParsedDate.getTime());

            Date dropoffParsedDate = dateFormat.parse("0000-00-0 00:00:00");
            Timestamp dropoffTimestamp = new java.sql.Timestamp(dropoffParsedDate.getTime());

            int passenger_count = 0;
            int vendor_ID = 0;

            for (TripEvent e : iterable) {

                vendor_ID = e.f0;

                Timestamp currentPickupTimestamp = e.f1;
                Timestamp currentDropoffTimestamp = e.f2;

                if(currentPickupTimestamp.before(pickupTimestamp))
                {
                    pickupTimestamp = currentPickupTimestamp;
                }
                if(currentDropoffTimestamp.after(dropoffTimestamp))
                {
                    dropoffTimestamp = currentDropoffTimestamp;
                }

                passenger_count = passenger_count + e.f3;
            }

            jfkAlarmEvent.set_VendorID(vendor_ID);
            jfkAlarmEvent.set_tpep_pickup_datetime(pickupTimestamp);
            jfkAlarmEvent.set_tpep_dropoff_datetime(dropoffTimestamp);
            jfkAlarmEvent.set_passenger_count(passenger_count);
            collector.collect(jfkAlarmEvent);
        }
    }
}
