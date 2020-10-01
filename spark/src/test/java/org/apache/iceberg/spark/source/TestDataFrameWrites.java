/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iceberg.spark.source;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
import org.apache.avro.generic.GenericData.Record;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.Files;
import org.apache.iceberg.PartitionField;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.avro.Avro;
import org.apache.iceberg.avro.AvroIterable;
import org.apache.iceberg.hadoop.HadoopTables;
import org.apache.iceberg.io.FileAppender;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.spark.SparkSchemaUtil;
import org.apache.iceberg.spark.data.AvroDataTest;
import org.apache.iceberg.spark.data.RandomData;
import org.apache.iceberg.spark.data.SparkAvroReader;
import org.apache.iceberg.types.Types;
import org.apache.spark.SparkException;
import org.apache.spark.TaskContext;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.MapPartitionsFunction;
import org.apache.spark.sql.Column;
import org.apache.spark.sql.DataFrameWriter;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SaveMode;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.catalyst.encoders.RowEncoder;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static org.apache.iceberg.spark.SparkSchemaUtil.convert;
import static org.apache.iceberg.spark.data.TestHelpers.assertEqualsSafe;
import static org.apache.iceberg.spark.data.TestHelpers.assertEqualsUnsafe;

@RunWith(Parameterized.class)
public abstract class TestDataFrameWrites extends AvroDataTest {
  private static final Configuration CONF = new Configuration();

  private final String format;

  @Parameterized.Parameters
  public static Object[][] parameters() {
    return new Object[][] {
        new Object[] { "parquet" },
        new Object[] { "avro" },
        new Object[] { "orc" }
    };
  }

  public TestDataFrameWrites(String format) {
    this.format = format;
  }

  private static SparkSession spark = null;
  private static JavaSparkContext sc = null;

  private Map<String, String> tableProperties;

  private org.apache.spark.sql.types.StructType sparkSchema = new org.apache.spark.sql.types.StructType(
      new org.apache.spark.sql.types.StructField[] {
          new org.apache.spark.sql.types.StructField(
              "optionalField",
              org.apache.spark.sql.types.DataTypes.StringType,
              true,
              org.apache.spark.sql.types.Metadata.empty()),
          new org.apache.spark.sql.types.StructField(
              "requiredField",
              org.apache.spark.sql.types.DataTypes.StringType,
              false,
              org.apache.spark.sql.types.Metadata.empty())
      });

  private Schema icebergSchema = new Schema(
      Types.NestedField.optional(1, "optionalField", Types.StringType.get()),
      Types.NestedField.required(2, "requiredField", Types.StringType.get()));

  private List<String> data0 = Arrays.asList(
      "{\"optionalField\": \"a1\", \"requiredField\": \"bid_001\"}",
      "{\"optionalField\": \"a2\", \"requiredField\": \"bid_002\"}");
  private List<String> data1 = Arrays.asList(
      "{\"optionalField\": \"d1\", \"requiredField\": \"bid_101\"}",
      "{\"optionalField\": \"d2\", \"requiredField\": \"bid_102\"}",
      "{\"optionalField\": \"d3\", \"requiredField\": \"bid_103\"}",
      "{\"optionalField\": \"d4\", \"requiredField\": \"bid_104\"}");

  @BeforeClass
  public static void startSpark() {
    TestDataFrameWrites.spark = SparkSession.builder().master("local[2]").getOrCreate();
    TestDataFrameWrites.sc = new JavaSparkContext(spark.sparkContext());
  }

  @AfterClass
  public static void stopSpark() {
    SparkSession currentSpark = TestDataFrameWrites.spark;
    TestDataFrameWrites.spark = null;
    TestDataFrameWrites.sc = null;
    currentSpark.stop();
  }

  @Override
  protected void writeAndValidate(Schema schema) throws IOException {
    File location = createTableFolder();
    Table table = createTable(schema, location);
    writeAndValidateWithLocations(table, location, new File(location, "data"));
  }

  @Test
  public void testWriteWithCustomDataLocation() throws IOException {
    File location = createTableFolder();
    File tablePropertyDataLocation = temp.newFolder("test-table-property-data-dir");
    Table table = createTable(new Schema(SUPPORTED_PRIMITIVES.fields()), location);
    table.updateProperties().set(
        TableProperties.WRITE_NEW_DATA_LOCATION, tablePropertyDataLocation.getAbsolutePath()).commit();
    writeAndValidateWithLocations(table, location, tablePropertyDataLocation);
  }

  @Test
  public void testWritePartitionedData() throws IOException {
    Schema schema = new Schema(SUPPORTED_PRIMITIVES.fields());
    PartitionSpec partitionSpec = PartitionSpec.builderFor(schema).identity("l").build();
    List<Record> expected = RandomData.generateList(schema, 100, 0L);
    // Make id unique so we can sort them for asserting equal
    for (int i = 0; i < 100; i++) {
      expected.get(i).put("id", Long.valueOf(i));
    }
    writeAndValidatePartitionedWrite(schema, partitionSpec, expected);
  }

  @Test
  public void testWriteWithDynamicallyLoadedLocationProvider() throws IOException {
    Schema schema = new Schema(SUPPORTED_PRIMITIVES.fields());
    PartitionSpec partitionSpec = PartitionSpec.builderFor(schema).identity("b").build();
    List<Record> expected = RandomData.generateList(schema, 100, 0L);
    // Make id unique so we can sort them for asserting equal
    for (int i = 0; i < 100; i++) {
      expected.get(i).put("id", Long.valueOf(i));
    }

    File pathTrue = temp.newFolder("true-data-location");
    File pathFalse = temp.newFolder("false-data-location");

    File location = createTableFolder();
    Table table = createTable(schema, partitionSpec, location);
    table.updateProperties()
        .set(TableProperties.DEFAULT_FILE_FORMAT, format)
        .set(TableProperties.LOCATION_PROVIDER_IMPL, TestLocationProvider.class.getCanonicalName())
        .set(TestLocationProvider.TRUE_PATH, pathTrue.getAbsolutePath())
        .set(TestLocationProvider.FALSE_PATH, pathFalse.getAbsolutePath())
        .commit();

    writePartitionedDataAndCheckRead(table, schema, partitionSpec, location, expected);

    table.currentSnapshot().addedFiles().forEach(f -> {
      Boolean oddOrEven = f.partition().get(0, Boolean.class);
      File expectedDataLocationPath = oddOrEven ? pathTrue : pathFalse;
      assertDirectoryHasFile(expectedDataLocationPath, f);
    });
    assertDataPathHasPartitionEncoding(table, partitionSpec
            .fields()
            .stream()
            .map(PartitionField::name)
            .collect(Collectors.toList()));
  }

  private void writePartitionedDataAndCheckRead(Table table,
                                                Schema originalSchema,
                                                PartitionSpec partitionSpec,
                                                File location,
                                                List<Record> expected) throws IOException {
    Schema tableSchema = table.schema(); // use the table schema because ids are reassigned

    Dataset<Row> df = createDataset(expected, tableSchema);

    // Need to sort within the source partition columns (not the transformed ones)
    Column[] sourcePartitionColumns = partitionSpec.fields().stream()
        .map(PartitionField::sourceId)
        .map(originalSchema::findColumnName)
        .map(Column::new).toArray(Column[]::new);

    DataFrameWriter<?> writer = df
        .sortWithinPartitions(sourcePartitionColumns)
        .write()
        .format("iceberg")
        .mode("overwrite");

    writer.save(location.toString());

    table.refresh();

    Dataset<Row> result = spark.read()
        .format("iceberg")
        .load(location.toString());

    // Sort first field of test SUPPORTED_PRIMITIVES unique long id.
    Iterator<Row> actualSorted = result.collectAsList().stream()
        .sorted(Comparator.comparing(r -> (Long) r.get(0)))
        .iterator();

    Iterator<Record> expectedSorted = expected.stream()
        .sorted(Comparator.comparing(r -> (Long) r.get(0)))
        .iterator();

    assertRecordsEqualRows(tableSchema, expectedSorted, actualSorted);
  }

  private void writeAndValidatePartitionedWrite(
                                                Schema schema,
                                                PartitionSpec partitionSpec,
                                                List<Record> expected) throws IOException {

    File location = createTableFolder();
    Table table = createTable(schema, partitionSpec, location);
    table.updateProperties().set(TableProperties.DEFAULT_FILE_FORMAT, format).commit();

    writePartitionedDataAndCheckRead(table, schema, partitionSpec, location, expected);
    assertAddedDataLocation(table, new File(location, "data"));
    assertDataPathHasPartitionEncoding(
            table,
            partitionSpec
                    .fields()
                    .stream()
                    .map(PartitionField::name)
                    .collect(Collectors.toList()));
  }

  private File createTableFolder() throws IOException {
    File parent = temp.newFolder("parquet");
    File location = new File(parent, "test");
    Assert.assertTrue("Mkdir should succeed", location.mkdirs());
    return location;
  }

  private Table createTable(Schema schema, File location) {
    return createTable(schema, PartitionSpec.unpartitioned(), location);
  }

  private Table createTable(Schema schema, PartitionSpec partitionSpec, File location) {
    HadoopTables tables = new HadoopTables(CONF);
    return tables.create(schema, partitionSpec, location.toString());
  }

  private void writeAndValidateWithLocations(Table table, File location, File expectedDataDir) throws IOException {
    Schema tableSchema = table.schema(); // use the table schema because ids are reassigned

    table.updateProperties().set(TableProperties.DEFAULT_FILE_FORMAT, format).commit();

    Iterable<Record> expected = RandomData.generate(tableSchema, 100, 0L);
    writeData(expected, tableSchema, location.toString());

    table.refresh();

    List<Row> actual = readTable(location.toString());

    Iterator<Record> expectedIter = expected.iterator();
    Iterator<Row> actualIter = actual.iterator();
    assertRecordsEqualRows(tableSchema, expectedIter, actualIter);
    assertAddedDataLocation(table, expectedDataDir);
  }

  private void assertRecordsEqualRows(Schema tableSchema, Iterator<Record> expectedIter, Iterator<Row> actualIter) {
    while (expectedIter.hasNext() && actualIter.hasNext()) {
      assertEqualsSafe(tableSchema.asStruct(), expectedIter.next(), actualIter.next());
    }
    Assert.assertEquals("Both iterators should be exhausted", expectedIter.hasNext(), actualIter.hasNext());
  }

  private void assertDataPathHasPartitionEncoding(Table table, List<String> derivedPartitionColumns) {
    List<String> partitionPath = derivedPartitionColumns.stream().map(s -> s + "=").collect(Collectors.toList());

    table.currentSnapshot().addedFiles().forEach(dataFile ->
            partitionPath.forEach(p ->
            Assert.assertTrue(
                String.format(
                    "File path should have partition encoding %s, but has: %s.",
                    derivedPartitionColumns.stream().collect(Collectors.joining(",")),
                    dataFile.path()), dataFile.path().toString().contains(p))
      )
    );
  }

  private void assertAddedDataLocation(Table table, File expectedDataDir) {
    table.currentSnapshot().addedFiles().forEach(dataFile ->
        assertDirectoryHasFile(expectedDataDir, dataFile));
  }

  private void assertDirectoryHasFile(File expectedDataDir, DataFile dataFile) {
    Assert.assertTrue(
        String.format(
            "File should have the parent directory %s, but has: %s.",
            expectedDataDir.getAbsolutePath(),
            dataFile.path()),
        URI.create(dataFile.path().toString()).getPath().startsWith(expectedDataDir.getAbsolutePath()));
  }

  private List<Row> readTable(String location) {
    Dataset<Row> result = spark.read()
        .format("iceberg")
        .load(location);

    return result.collectAsList();
  }

  private void writeData(Iterable<Record> records, Schema schema, String location) throws IOException {
    Dataset<Row> df = createDataset(records, schema);
    DataFrameWriter<?> writer = df.write().format("iceberg").mode("append");
    writer.save(location);
  }

  private void writeDataWithFailOnPartition(Iterable<Record> records, Schema schema, String location)
      throws IOException, SparkException {
    final int numPartitions = 10;
    final int partitionToFail = new Random().nextInt(numPartitions);
    MapPartitionsFunction<Row, Row> failOnFirstPartitionFunc = (MapPartitionsFunction<Row, Row>) input -> {
      int partitionId = TaskContext.getPartitionId();

      if (partitionId == partitionToFail) {
        throw new SparkException(String.format("Intended exception in partition %d !", partitionId));
      }
      return input;
    };

    Dataset<Row> df = createDataset(records, schema)
        .repartition(numPartitions)
        .mapPartitions(failOnFirstPartitionFunc, RowEncoder.apply(convert(schema)));
    // This trick is needed because Spark 3 handles decimal overflow in RowEncoder which "changes"
    // nullability of the column to "true" regardless of original nullability.
    // Setting "check-nullability" option to "false" doesn't help as it fails at Spark analyzer.
    Dataset<Row> convertedDf = df.sqlContext().createDataFrame(df.rdd(), convert(schema));
    DataFrameWriter<?> writer = convertedDf.write().format("iceberg").mode("append");
    writer.save(location);
  }

  private Dataset<Row> createDataset(Iterable<Record> records, Schema schema) throws IOException {
    // this uses the SparkAvroReader to create a DataFrame from the list of records
    // it assumes that SparkAvroReader is correct
    File testFile = temp.newFile();
    Assert.assertTrue("Delete should succeed", testFile.delete());

    try (FileAppender<Record> writer = Avro.write(Files.localOutput(testFile))
        .schema(schema)
        .named("test")
        .build()) {
      for (Record rec : records) {
        writer.add(rec);
      }
    }

    // make sure the dataframe matches the records before moving on
    List<InternalRow> rows = Lists.newArrayList();
    try (AvroIterable<InternalRow> reader = Avro.read(Files.localInput(testFile))
        .createReaderFunc(SparkAvroReader::new)
        .project(schema)
        .build()) {

      Iterator<Record> recordIter = records.iterator();
      Iterator<InternalRow> readIter = reader.iterator();
      while (recordIter.hasNext() && readIter.hasNext()) {
        InternalRow row = readIter.next();
        assertEqualsUnsafe(schema.asStruct(), recordIter.next(), row);
        rows.add(row);
      }
      Assert.assertEquals("Both iterators should be exhausted", recordIter.hasNext(), readIter.hasNext());
    }

    JavaRDD<InternalRow> rdd = sc.parallelize(rows);
    return spark.internalCreateDataFrame(JavaRDD.toRDD(rdd), convert(schema), false);
  }

  @Test
  public void testNullableWithWriteOption() throws IOException {
    Assume.assumeTrue("Spark 3.0 rejects writing nulls to a required column", spark.version().startsWith("2"));

    File location = new File(temp.newFolder("parquet"), "test");
    String sourcePath = String.format("%s/nullable_poc/sourceFolder/", location.toString());
    String targetPath = String.format("%s/nullable_poc/targetFolder/", location.toString());

    tableProperties = ImmutableMap.of(TableProperties.WRITE_NEW_DATA_LOCATION, targetPath);

    // read this and append to iceberg dataset
    spark
        .read().schema(sparkSchema).json(
        JavaSparkContext.fromSparkContext(spark.sparkContext()).parallelize(data1))
        .write().parquet(sourcePath);

    // this is our iceberg dataset to which we will append data
    new HadoopTables(spark.sessionState().newHadoopConf())
        .create(
            icebergSchema,
            PartitionSpec.builderFor(icebergSchema).identity("requiredField").build(),
            tableProperties,
            targetPath);

    // this is the initial data inside the iceberg dataset
    spark
        .read().schema(sparkSchema).json(
        JavaSparkContext.fromSparkContext(spark.sparkContext()).parallelize(data0))
        .write().format("iceberg").mode(SaveMode.Append).save(targetPath);

    // read from parquet and append to iceberg w/ nullability check disabled
    spark
        .read().schema(SparkSchemaUtil.convert(icebergSchema)).parquet(sourcePath)
        .write().format("iceberg").option("check-nullability", false).mode(SaveMode.Append).save(targetPath);

    // read all data
    List<Row> rows = spark.read().format("iceberg").load(targetPath).collectAsList();
    Assert.assertEquals("Should contain 6 rows", 6, rows.size());
  }

  @Test
  public void testNullableWithSparkSqlOption() throws IOException {
    Assume.assumeTrue("Spark 3.0 rejects writing nulls to a required column", spark.version().startsWith("2"));

    File location = new File(temp.newFolder("parquet"), "test");
    String sourcePath = String.format("%s/nullable_poc/sourceFolder/", location.toString());
    String targetPath = String.format("%s/nullable_poc/targetFolder/", location.toString());

    tableProperties = ImmutableMap.of(TableProperties.WRITE_NEW_DATA_LOCATION, targetPath);

    // read this and append to iceberg dataset
    spark
        .read().schema(sparkSchema).json(
        JavaSparkContext.fromSparkContext(spark.sparkContext()).parallelize(data1))
        .write().parquet(sourcePath);

    SparkSession newSparkSession = SparkSession.builder()
        .master("local[2]")
        .appName("NullableTest")
        .config("spark.sql.iceberg.check-nullability", false)
        .getOrCreate();

    // this is our iceberg dataset to which we will append data
    new HadoopTables(newSparkSession.sessionState().newHadoopConf())
        .create(
            icebergSchema,
            PartitionSpec.builderFor(icebergSchema).identity("requiredField").build(),
            tableProperties,
            targetPath);

    // this is the initial data inside the iceberg dataset
    newSparkSession
        .read().schema(sparkSchema).json(
        JavaSparkContext.fromSparkContext(spark.sparkContext()).parallelize(data0))
        .write().format("iceberg").mode(SaveMode.Append).save(targetPath);

    // read from parquet and append to iceberg
    newSparkSession
        .read().schema(SparkSchemaUtil.convert(icebergSchema)).parquet(sourcePath)
        .write().format("iceberg").mode(SaveMode.Append).save(targetPath);

    // read all data
    List<Row> rows = newSparkSession.read().format("iceberg").load(targetPath).collectAsList();
    Assert.assertEquals("Should contain 6 rows", 6, rows.size());

  }

  @Test
  public void testFaultToleranceOnWrite() throws IOException {
    File location = createTableFolder();
    Schema schema = new Schema(SUPPORTED_PRIMITIVES.fields());
    Table table = createTable(schema, location);

    Iterable<Record> records = RandomData.generate(schema, 100, 0L);
    writeData(records, schema, location.toString());

    table.refresh();

    Snapshot snapshotBeforeFailingWrite = table.currentSnapshot();
    List<Row> resultBeforeFailingWrite = readTable(location.toString());

    try {
      Iterable<Record> records2 = RandomData.generate(schema, 100, 0L);
      writeDataWithFailOnPartition(records2, schema, location.toString());
      Assert.fail("The query should fail");
    } catch (SparkException e) {
      // no-op
    }

    table.refresh();

    Snapshot snapshotAfterFailingWrite = table.currentSnapshot();
    List<Row> resultAfterFailingWrite = readTable(location.toString());

    Assert.assertEquals(snapshotAfterFailingWrite, snapshotBeforeFailingWrite);
    Assert.assertEquals(resultAfterFailingWrite, resultBeforeFailingWrite);
  }
}
