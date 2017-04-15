package poc.sql.dataloader;

import au.com.bytecode.opencsv.CSVWriter;
import lombok.extern.slf4j.Slf4j;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.sql.*;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.Metadata;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import poc.commons.SparkSessionInitializer;

import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.row_number;
import static org.apache.spark.sql.functions.lit;
import static org.apache.spark.sql.functions.*;
import static scala.collection.JavaConversions.seqAsJavaList;

import org.apache.spark.sql.expressions.*;
import poc.sql.integrity.internal.helper.FileHelper;

/***
 * Created by elevy on 15-Apr-17.
 **/
@Slf4j
public class SparkSequenceService {
    private String SEQ_COLUMN_NAME = "tr-sequence";

    public static void main(String[] args) {
        SparkSequenceService sparkSequenceService = new SparkSequenceService();
        sparkSequenceService.addSequenceUsingRowNumber(400);
    }

    public void addSequenceUsingRowNumber(long offset){
        SparkSession sparkSession =  new SparkSessionInitializer().getSparkSession();
        SQLContext sqlContext = new SQLContext(sparkSession);
        FileHelper fileHelper = new FileHelper();
        Dataset<Row> rowDataset = fileHelper.readCSV(sqlContext, System.getProperty("user.dir") + "/_resources/data/integrity/proxy_fixed.csv");
        Dataset<Row> rowDatasetWithSeq = rowDataset.withColumn(SEQ_COLUMN_NAME, row_number().over(Window.partitionBy(lit(1)).orderBy(lit(1))).$plus(lit(offset)));
//        rowDatasetWithSeq = rowDatasetWithSeq.withColumn(SEQ_COLUMN_NAME, col(SEQ_COLUMN_NAME).$plus(lit(100)));
        rowDatasetWithSeq = rowDatasetWithSeq.select(SEQ_COLUMN_NAME, rowDataset.columns());
        rowDatasetWithSeq.show();
//        fileHelper.writeCSV(rowDatasetWithSeq, System.getProperty("user.dir") + "/_resources/data/integrity/proxy_fixed_with_index2.csv");
    }


    public Dataset<Row> addSequenceColumnToDS(JavaRDD<String[]> nonCorruptedLines, Broadcast<List<DataFieldItem>> bHeaders, String tempPath, Broadcast<String> bDelimiter, Broadcast<Integer> bThreshold) {
        SparkSession sparkSession =  new SparkSessionInitializer().getSparkSession();
        Dataset<Row> dataSourceWithSeq;
        Integer threshold = bThreshold.getValue();
        int numOfCol = bHeaders.getValue().size();
        log.info("DataSource: index threshold was set to {} while #OfFeature is {}", threshold, numOfCol);
        if(numOfCol > threshold) {
            //Add sequence ID column while writing and reading the csv
            log.info("DataSource: Generate SEQ using WR CSV");
            dataSourceWithSeq = addDSSequenceUsingWriteRead(sparkSession, nonCorruptedLines, SEQ_COLUMN_NAME, bHeaders, tempPath, bDelimiter);
        }else{
            //Add sequence ID column while using createDataFrame()
            log.info("DataSource: Generate SEQ using spark.createDataFrame()");
            JavaRDD<Row> nonCorruptedLinesRow = nonCorruptedLines.map(RowFactory::create);

            List<StructField> fields = bHeaders.getValue().stream()
                    .sorted(Comparator.comparingInt(DataFieldItem::getPosition))
                    .map(df -> DataTypes.createStructField(df.getName(), DataTypes.StringType, true))
                    .collect(Collectors.toList());

            dataSourceWithSeq = addSequenceColumn(sparkSession, nonCorruptedLinesRow, fields, SEQ_COLUMN_NAME);
        }
        return dataSourceWithSeq;
    }

    public Dataset<Row> addSequenceColumnToDF(Dataset<Row> rowDataset, String tempPath, Broadcast<Integer> bThreshold) {
        SparkSession sparkSession =  new SparkSessionInitializer().getSparkSession();
        Dataset<Row> dataFrameWithSeq;
        Integer threshold = bThreshold.getValue();
        int numOfCol = rowDataset.schema().fields().length;
        log.info("ataFrame: index threshold was set to {} while #OfFeature is {}", threshold, numOfCol);
        if(numOfCol >  threshold) {
            //Add sequence ID column while writing and reading the csv
            log.info("DataFrame: Generate SEQ using WR CSV");
            dataFrameWithSeq = addDFSequenceUsingWriteRead(sparkSession, rowDataset, SEQ_COLUMN_NAME, tempPath);
        }else{
            //Add sequence ID column while using createDataFrame()
            log.info("DataFrame: Generate SEQ using spark.createDataFrame()");
            JavaRDD<Row> rddOfRow = rowDataset.toJavaRDD();
            List<StructField> fields = new ArrayList<>(Arrays.asList(rowDataset.schema().fields()));
            dataFrameWithSeq = addSequenceColumn(sparkSession, rddOfRow, fields, SEQ_COLUMN_NAME);
        }
        return dataFrameWithSeq;
    }

    private Dataset<Row> addDFSequenceUsingWriteRead(SparkSession sparkSession, Dataset<Row> dataFrameToSave, String idColumnName, String tempPath) {
        //Add index to RDD
        log.debug("DataFrame: Add index to RDD using addDFSequenceUsingWriteRead");
        JavaPairRDD<Row, Long> rowLongJavaPairRDD = dataFrameToSave.toJavaRDD().zipWithIndex();
        JavaRDD<String> rddWithIndex = rowLongJavaPairRDD.map(t -> {
            Row row = t._1();
            List<Object> values = seqAsJavaList(row.toSeq());
            String[] strValues = values.stream().map(Object::toString).toArray(String[]::new);
            StringWriter sWriter = new StringWriter();
            CSVWriter csvWriter = new CSVWriter(sWriter);
            csvWriter.writeNext(strValues);
            csvWriter.close();
            return t._2() + "," + sWriter.toString();
        });
        writeRDDAsCsv(tempPath, rddWithIndex);

        //add the sequence id to the list of field as the first field
        StructField[] fields = dataFrameToSave.schema().fields();
        StructType dataSourceSchema = buildStructType(idColumnName, fields);
        Dataset<Row> csvDataSet = readCsvAsDataset(sparkSession, tempPath, dataSourceSchema);

        return replaceNullValues(csvDataSet);
    }

    private Dataset<Row> addDSSequenceUsingWriteRead(SparkSession sparkSession, JavaRDD<String[]> nonCorruptedLines, String idColumnName, Broadcast<List<DataFieldItem>> bHeaders, String tempPath, Broadcast<String> bDelimiter) {
        //Add index to RDD
        log.debug("DataSource: Add index to RDD using addDSSequenceUsingWriteRead");
        JavaPairRDD<String[], Long> rddPair = nonCorruptedLines.zipWithIndex();
        JavaRDD<String> rddWithIndex = rddPair.map(t -> {
            String[] splits = t._1();
            StringBuilder sb = new StringBuilder(String.valueOf(t._2()));
            Arrays.stream(splits).forEach(token -> {
                sb.append(bDelimiter.getValue());
                sb.append(token);
            });
            return sb.toString();
        });
        writeRDDAsCsv(tempPath, rddWithIndex);

        List<DataFieldItem> headers = bHeaders.getValue();
        StructField[] fields = headers.stream()
                .sorted(Comparator.comparingInt(DataFieldItem::getPosition))
                .map(df -> DataTypes.createStructField(df.getName(), DataTypes.StringType, true))
                .toArray(StructField[]::new);
        StructType dataSourceSchema = buildStructType(idColumnName, fields);

        //read csv as dataset
        Dataset<Row> csvDataSet = readCsvAsDataset(sparkSession, tempPath, dataSourceSchema);
        Dataset<Row> filteredHeader = csvDataSet.filter(col(fields[0].name()).notEqual(bHeaders.getValue().get(0).getName()));

        return replaceNullValues(filteredHeader);
    }

    private Dataset<Row> addSequenceColumn(SparkSession spark, JavaRDD<Row> rddOfRow, List<StructField> fields, String idColumnName) {
        //Add index to RDD
        log.debug("Add index to RDD using addSequenceColumn");
        JavaPairRDD<Row, Long> rddPair = rddOfRow.zipWithIndex();
        JavaRDD<Row> rddWithIndex = rddPair.map(t -> {
            List<Object> origRow = seqAsJavaList(t._1().toSeq());
            List<Object> newList = new ArrayList<>(origRow.size() + 1);
            newList.add(t._2()); //ID
            newList.addAll(origRow); //Rest of row
            return RowFactory.create(newList.toArray());
        });

        //Build new schema for the new dataset
        fields.add(0, new StructField(idColumnName, DataTypes.LongType, true, Metadata.empty()));
        StructField[] extendedFields = fields.stream().toArray(StructField[]::new);
        StructType extendedSchema = new StructType(extendedFields);

        return spark.createDataFrame(rddWithIndex, extendedSchema);
    }

    private void writeRDDAsCsv(String tempPath, JavaRDD<String> rddWithIndex) {
        log.debug("Delete previous temp csv file from {}", tempPath);
        deleteTempPath(tempPath);
        log.debug("Write RDD as a csv to temp folder {}", tempPath);
        rddWithIndex.saveAsTextFile(tempPath);
    }

    private StructType buildStructType(String idColumnName, StructField[] fields) {
        log.debug("Add index column to Schema (StructType)");
        //add the sequence id to the list of field as the first field
        StructField[] fieldsWithId = new StructField[fields.length + 1];
        fieldsWithId[0] = new StructField(idColumnName, DataTypes.LongType, true, Metadata.empty());
        System.arraycopy(fields, 0, fieldsWithId, 1, fields.length);

        return DataTypes.createStructType(fieldsWithId);
    }

    private Dataset<Row> readCsvAsDataset(SparkSession sparkSession, String tempPath, StructType dataSourceSchema) {
        //read csv as dataset
        log.debug("Read csv as DataSet from temp folder {}", tempPath);
        return sparkSession.read()
                .schema(dataSourceSchema)
                .option("header", false)
                .csv(tempPath);
    }

    private Dataset<Row> replaceNullValues(Dataset<Row> csvDataSet) {
        DataFrameNaFunctions functions = new DataFrameNaFunctions(csvDataSet);
        return functions.fill("");
    }

    private void deleteTempPath(String tempTextDFWithIndexPath) {
        try {
            log.debug("going to delete folder tree {}", tempTextDFWithIndexPath);
            FileSystem fs = FileSystem.get(new URI(tempTextDFWithIndexPath), new Configuration());
            Path path = new Path(tempTextDFWithIndexPath);
            if(fs.exists(path)) {
                fs.delete(path, true);
            }
        } catch (IOException | URISyntaxException e) {
            log.warn("Fail to delete temp csv file from {} : {}",tempTextDFWithIndexPath, e.getMessage());
            e.printStackTrace();
        }
    }
}
