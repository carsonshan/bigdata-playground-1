package poc.commons;

import org.apache.spark.sql.SparkSession;

/**
 * Created by elevy on 17-Feb-17.
 */
public class SparkSessionInitializer {
    public SparkSession getSparkSession() {
        String workingDir = System.getProperty("user.dir");
        System.out.println("Working Directory = " + workingDir);

        System.setProperty("hadoop.home.dir", workingDir + "/_resources/hadoop_home");
        return SparkSession
                .builder()
                .appName("Java Spark SQL basic example")
                .master("local[8]")
                .config("spark.sql.warehouse.dir", workingDir + "/_resources/spark-warehouse")
                .getOrCreate();
    }
}
