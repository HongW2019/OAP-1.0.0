/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql

import java.sql.{Date, Timestamp}

import org.apache.spark.SparkConf
import org.apache.spark.sql.catalyst.expressions.aggregate.ApproximatePercentile
import org.apache.spark.sql.catalyst.expressions.aggregate.ApproximatePercentile.DEFAULT_PERCENTILE_ACCURACY
import org.apache.spark.sql.catalyst.expressions.aggregate.ApproximatePercentile.PercentileDigest
import org.apache.spark.sql.catalyst.util.DateTimeUtils
import org.apache.spark.sql.test.SharedSparkSession

/**
 * End-to-end tests for approximate percentile aggregate function.
 */
class ApproximatePercentileQuerySuite extends QueryTest with SharedSparkSession {
  import testImplicits._

  override def sparkConf: SparkConf =
    super.sparkConf
      .setAppName("test")
      .set("spark.sql.parquet.columnarReaderBatchSize", "4096")
      .set("spark.sql.sources.useV1SourceList", "avro")
      .set("spark.sql.extensions", "com.intel.oap.ColumnarPlugin")
      .set("spark.sql.execution.arrow.maxRecordsPerBatch", "4096")
      //.set("spark.shuffle.manager", "org.apache.spark.shuffle.sort.ColumnarShuffleManager")
      .set("spark.memory.offHeap.enabled", "true")
      .set("spark.memory.offHeap.size", "10m")
      .set("spark.sql.join.preferSortMergeJoin", "false")
      .set("spark.sql.columnar.codegen.hashAggregate", "false")
      .set("spark.oap.sql.columnar.wholestagecodegen", "false")
      .set("spark.sql.columnar.window", "false")
      .set("spark.unsafe.exceptionOnMemoryLeak", "false")
      //.set("spark.sql.columnar.tmp_dir", "/codegen/nativesql/")
      .set("spark.sql.columnar.sort.broadcastJoin", "true")
      .set("spark.oap.sql.columnar.preferColumnar", "true")

  private val table = "percentile_test"

  test("percentile_approx, single percentile value") {
    withTempView(table) {
      (1 to 1000).toDF("col").createOrReplaceTempView(table)
      checkAnswer(
        spark.sql(
          s"""
             |SELECT
             |  percentile_approx(col, 0.25),
             |  percentile_approx(col, 0.5),
             |  percentile_approx(col, 0.75d),
             |  percentile_approx(col, 0.0),
             |  percentile_approx(col, 1.0),
             |  percentile_approx(col, 0),
             |  percentile_approx(col, 1)
             |FROM $table
           """.stripMargin),
        Row(250D, 500D, 750D, 1D, 1000D, 1D, 1000D)
      )
    }
  }

  test("percentile_approx, the first element satisfies small percentages") {
    withTempView(table) {
      (1 to 10).toDF("col").createOrReplaceTempView(table)
      checkAnswer(
        spark.sql(
          s"""
             |SELECT
             |  percentile_approx(col, array(0.01, 0.1, 0.11))
             |FROM $table
           """.stripMargin),
        Row(Seq(1, 1, 2))
      )
    }
  }

  test("percentile_approx, array of percentile value") {
    withTempView(table) {
      (1 to 1000).toDF("col").createOrReplaceTempView(table)
      checkAnswer(
        spark.sql(
          s"""SELECT
             |  percentile_approx(col, array(0.25, 0.5, 0.75D)),
             |  count(col),
             |  percentile_approx(col, array(0.0, 1.0)),
             |  sum(col)
             |FROM $table
           """.stripMargin),
        Row(Seq(250D, 500D, 750D), 1000, Seq(1D, 1000D), 500500)
      )
    }
  }

  ignore("percentile_approx, different column types") {
    withTempView(table) {
      val intSeq = 1 to 1000
      val data: Seq[(java.math.BigDecimal, Date, Timestamp)] = intSeq.map { i =>
        (new java.math.BigDecimal(i), DateTimeUtils.toJavaDate(i), DateTimeUtils.toJavaTimestamp(i))
      }
      data.toDF("cdecimal", "cdate", "ctimestamp").createOrReplaceTempView(table)
      checkAnswer(
        spark.sql(
          s"""SELECT
             |  percentile_approx(cdecimal, array(0.25, 0.5, 0.75D)),
             |  percentile_approx(cdate, array(0.25, 0.5, 0.75D)),
             |  percentile_approx(ctimestamp, array(0.25, 0.5, 0.75D))
             |FROM $table
           """.stripMargin),
        Row(
          Seq("250.000000000000000000", "500.000000000000000000", "750.000000000000000000")
              .map(i => new java.math.BigDecimal(i)),
          Seq(250, 500, 750).map(DateTimeUtils.toJavaDate),
          Seq(250, 500, 750).map(i => DateTimeUtils.toJavaTimestamp(i.toLong)))
      )
    }
  }

  test("percentile_approx, multiple records with the minimum value in a partition") {
    withTempView(table) {
      spark.sparkContext.makeRDD(Seq(1, 1, 2, 1, 1, 3, 1, 1, 4, 1, 1, 5), 4).toDF("col")
        .createOrReplaceTempView(table)
      checkAnswer(
        spark.sql(s"SELECT percentile_approx(col, array(0.5)) FROM $table"),
        Row(Seq(1.0D))
      )
    }
  }

  test("percentile_approx, with different accuracies") {

    withTempView(table) {
      val tableCount = 1000
      (1 to tableCount).toDF("col").createOrReplaceTempView(table)

      // With different accuracies
      val accuracies = Array(1, 10, 100, 1000, 10000)
      val expectedPercentiles = Array(100D, 200D, 250D, 314D, 777D)
      for (accuracy <- accuracies) {
        for (expectedPercentile <- expectedPercentiles) {
          val df = spark.sql(
            s"""SELECT
               | percentile_approx(col, $expectedPercentile/$tableCount, $accuracy)
               |FROM $table
             """.stripMargin)
          val approximatePercentile = df.collect().head.getInt(0)
          val error = Math.abs(approximatePercentile - expectedPercentile)
          assert(error <= math.floor(tableCount.toDouble / accuracy.toDouble))
        }
      }
    }
  }

  test("percentile_approx, supports constant folding for parameter accuracy and percentages") {
    withTempView(table) {
      (1 to 1000).toDF("col").createOrReplaceTempView(table)
      checkAnswer(
        spark.sql(s"SELECT percentile_approx(col, array(0.25 + 0.25D), 200 + 800) FROM $table"),
        Row(Seq(499))
      )
    }
  }

  test("percentile_approx(), aggregation on empty input table, no group by") {
    withTempView(table) {
      Seq.empty[Int].toDF("col").createOrReplaceTempView(table)
      checkAnswer(
        spark.sql(s"SELECT sum(col), percentile_approx(col, 0.5) FROM $table"),
        Row(null, null)
      )
    }
  }

  test("percentile_approx(), aggregation on empty input table, with group by") {
    withTempView(table) {
      Seq.empty[Int].toDF("col").createOrReplaceTempView(table)
      checkAnswer(
        spark.sql(s"SELECT sum(col), percentile_approx(col, 0.5) FROM $table GROUP BY col"),
        Seq.empty[Row]
      )
    }
  }

  test("percentile_approx(null), aggregation with group by") {
    withTempView(table) {
      (1 to 1000).map(x => (x % 3, x)).toDF("key", "value").createOrReplaceTempView(table)
      checkAnswer(
        spark.sql(
          s"""SELECT
             |  key,
             |  percentile_approx(null, 0.5)
             |FROM $table
             |GROUP BY key
           """.stripMargin),
        Seq(
          Row(0, null),
          Row(1, null),
          Row(2, null))
      )
    }
  }

  test("percentile_approx(null), aggregation without group by") {
    withTempView(table) {
      (1 to 1000).map(x => (x % 3, x)).toDF("key", "value").createOrReplaceTempView(table)
      checkAnswer(
        spark.sql(
          s"""SELECT
              |  percentile_approx(null, 0.5),
              |  sum(null),
              |  percentile_approx(null, 0.5)
              |FROM $table
           """.stripMargin),
         Row(null, null, null)
      )
    }
  }

  test("percentile_approx(col, ...), input rows contains null, with out group by") {
    withTempView(table) {
      (1 to 1000).map(Integer.valueOf(_)).flatMap(Seq(null: Integer, _)).toDF("col")
        .createOrReplaceTempView(table)
      checkAnswer(
        spark.sql(
          s"""SELECT
              |  percentile_approx(col, 0.5),
              |  sum(null),
              |  percentile_approx(col, 0.5)
              |FROM $table
           """.stripMargin),
        Row(500D, null, 500D))
    }
  }

  test("percentile_approx(col, ...), input rows contains null, with group by") {
    withTempView(table) {
      val rand = new java.util.Random()
      (1 to 1000)
        .map(Integer.valueOf(_))
        .map(v => (Integer.valueOf(v % 2), v))
        // Add some nulls
        .flatMap(Seq(_, (null: Integer, null: Integer)))
        .toDF("key", "value").createOrReplaceTempView(table)
      checkAnswer(
        spark.sql(
          s"""SELECT
              |  percentile_approx(value, 0.5),
              |  sum(value),
              |  percentile_approx(value, 0.5)
              |FROM $table
              |GROUP BY key
           """.stripMargin),
        Seq(
          Row(499.0D, 250000, 499.0D),
          Row(500.0D, 250500, 500.0D),
          Row(null, null, null))
      )
    }
  }

  test("percentile_approx(col, ...) works in window function") {
    withTempView(table) {
      val data = (1 to 10).map(v => (v % 2, v))
      data.toDF("key", "value").createOrReplaceTempView(table)

      val query = spark.sql(
        s"""
           |SElECT percentile_approx(value, 0.5)
           |OVER
           |  (PARTITION BY key ORDER BY value ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)
           |    AS percentile
           |FROM $table
           """.stripMargin)

      val expected = data.groupBy(_._1).toSeq.flatMap { group =>
        val (key, values) = group
        val sortedValues = values.map(_._2).sorted

        var outputRows = Seq.empty[Row]
        var i = 0

        val percentile = new PercentileDigest(1.0 / DEFAULT_PERCENTILE_ACCURACY)
        sortedValues.foreach { value =>
          percentile.add(value)
          outputRows :+= Row(percentile.getPercentiles(Array(0.5D)).head)
        }
        outputRows
      }

      checkAnswer(query, expected)
    }
  }

  test("SPARK-24013: unneeded compress can cause performance issues with sorted input") {
    val buffer = new PercentileDigest(1.0D / ApproximatePercentile.DEFAULT_PERCENTILE_ACCURACY)
    var compressCounts = 0
    (1 to 10000000).foreach { i =>
      buffer.add(i)
      if (buffer.isCompressed) compressCounts += 1
    }
    assert(compressCounts > 0)
    buffer.quantileSummaries
    assert(buffer.isCompressed)
  }
}
