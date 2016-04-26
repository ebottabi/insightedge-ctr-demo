package io.insightedge.demo.ctr

import java.text.SimpleDateFormat
import java.util.{Calendar, Date}

import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.ml.classification.LogisticRegression
import org.apache.spark.ml.evaluation.BinaryClassificationEvaluator
import org.apache.spark.ml.feature.{OneHotEncoder, StringIndexer, VectorAssembler}
import org.apache.spark.ml.param.ParamMap
import org.apache.spark.ml.tuning.{CrossValidator, ParamGridBuilder}
import org.apache.spark.ml.util.Identifiable
import org.apache.spark.ml.{Pipeline, Transformer}
import org.apache.spark.mllib.classification.LogisticRegressionModel
import org.apache.spark.mllib.evaluation.BinaryClassificationMetrics
import org.apache.spark.mllib.linalg.Vector
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.{DoubleType, IntegerType, StructField, StructType}
import org.apache.spark.sql.{DataFrame, Row, SQLContext}
import org.apache.spark.{SparkConf, SparkContext}

import scala.collection.mutable.ListBuffer

/**
  * @author Oleksiy_Dyagilev
  */
object Main {

  def main(args: Array[String]) = {

    val startTime = System.currentTimeMillis()

    val trainCsvPath = "/home/pivot/Downloads/avazu-ctr/train_1M"
    val testCsvPath = "/home/pivot/Downloads/avazu-ctr/test_100"

    val sc = new SparkContext(new SparkConf().setAppName("CTR").setMaster("local[*]"))

    sc.setLogLevel("ERROR")

    val sql = new SQLContext(sc)

    val rawTrainDf = loadCsvFile(sql, trainCsvPath, hasClickColumn = true)
    val rawTestDf = loadCsvFile(sql, testCsvPath, hasClickColumn = false)

    val (encodedTrainDf, encodedTestDf) = encodeLabels(
      transformHour(rawTrainDf),
      transformHour(rawTestDf)
    )

//    val Array(training, validation) = assembleFeatures(encodedTrainDf)
//      .select("features", "click")
//      .map(rowToLabelPoint)
//      .randomSplit(Array(0.8, 0.2), seed = 17)

    // train

    //    val model = new LogisticRegressionWithLBFGS()
    //      .setNumClasses(2)
    //      .run(training)
    //
    //    // Clear the prediction threshold so the model will return probabilities
    //    model.clearThreshold
    //
    //    // Compute raw scores on the test set.
    //    val predictionAndLabels = validation.map { case LabeledPoint(label, features) =>
    //      val prediction = model.predict(features)
    //      (prediction, label)
    //    }
    //
    //    calcMetrics(predictionAndLabels)


    //    val hourTransformer = new HourTransformer()
    val assembler = new VectorAssembler()
      .setInputCols(categoricalColumnsVectors.toArray)
      .setOutputCol("features")

    val lr = new LogisticRegression().setLabelCol("click")

    val pipeline = new Pipeline().setStages(Array(assembler, lr))

    val paramGrid = new ParamGridBuilder()
      .addGrid(lr.regParam, Array(0.1, 0.01))
      .build()

    val cv = new CrossValidator()
      .setEstimator(pipeline)
      .setEvaluator(new BinaryClassificationEvaluator().setLabelCol("click"))
      .setEstimatorParamMaps(paramGrid)
      .setNumFolds(2) // Use 3+ in practice

    val cvModel = cv.fit(encodedTrainDf)

    cvModel.avgMetrics.foreach(m => println("METRICS = " + m))

    cvModel.transform(encodedTestDf).show()

//    val model = pipeline.fit(encodedTrainDf)
//
//    val prediction = model.transform(encodedTestDf).select("features", "probability", "prediction")
//    prediction.show()



    // predict Kaggle test data
    //    kaggleTest(sql, model, encodedTestDf)

    val endTime = System.currentTimeMillis()
    println("time taken(s): " + (endTime - startTime) / 1000)
  }

  class HourTransformer(override val uid: String) extends Transformer {
    def this() = this(Identifiable.randomUID("hourTransformer"))

    override def transform(df: DataFrame): DataFrame = {
      val toYear = udf[Int, String](s => DateUtils.parse(s)._1)
      val toMonth = udf[Int, String](s => DateUtils.parse(s)._2)
      val toDay = udf[Int, String](s => DateUtils.parse(s)._3)
      val toHour = udf[Int, String](s => DateUtils.parse(s)._4)

      df.withColumn("time_year", toYear(df("hour")))
        .withColumn("time_month", toMonth(df("hour")))
        .withColumn("time_day", toDay(df("hour")))
        .withColumn("time_hour", toHour(df("hour")))
        .drop("hour")
    }

    @DeveloperApi
    override def transformSchema(schema: StructType): StructType = {
      val newCols = Seq(
        StructField("time_year", IntegerType, nullable = false),
        StructField("time_month", IntegerType, nullable = false),
        StructField("time_day", IntegerType, nullable = false),
        StructField("time_hour", IntegerType, nullable = false)
      )

      StructType(schema.filterNot(_.name == "hour") ++ newCols)
    }

    override def copy(extra: ParamMap): Transformer = defaultCopy(extra)
  }


  def rowToLabelPoint(r: Row) = LabeledPoint(r.getAs[Int]("click").toDouble, r.getAs[Vector]("features"))

  def kaggleTest(sql: SQLContext, model: LogisticRegressionModel, encodedTestDf: DataFrame) = {

    val test = assembleFeatures(encodedTestDf).select("id", "features")

    val outRdd = test.map { r =>
      val features = r.getAs[Vector]("features")
      val id = r.getAs[String]("id")
      val score = model.predict(features)
      s"$id,$score"
    }

    val header = sql.sparkContext.parallelize(Seq("id,click"))

    // TODO: to save to a single file
    val outDir = "/home/pivot/Downloads/avazu-ctr/test_out_" + new Date()
    (header ++ outRdd).repartition(1).saveAsTextFile(outDir)
    Utils.zipPredictionFile(outDir + "/part-00000", outDir + "/prediction.zip")
    println("done")
  }

  def loadCsvFile(sqlContext: SQLContext, csvPath: String, hasClickColumn: Boolean): DataFrame = {
    val df = sqlContext.read
      .format("com.databricks.spark.csv")
      .option("header", "true")
      .option("inferSchema", "false")
      .load(csvPath)

    val selectCols = ListBuffer(
      df("id"),
      //      df("device_id"),
      //      df("device_ip"),
      df("device_model"),
      df("device_type"),
      df("device_conn_type"),
      df("hour"),
      df("C1"),
      df("banner_pos"),
      //      df("site_id"),
      //      df("site_domain"),
      df("site_category"),
      //      df("app_id"),
      df("app_domain"),
      df("app_category"),
      df("C14"),
      df("C15"),
      df("C16"),
      df("C17"),
      df("C18"),
      df("C19"),
      df("C20"),
      df("C21")
    )

    // click col is only in train dataset, it's missing in the test dataset, but we want to keep the schema the same
    // so we can union datasets later, so for test dataset we add fictive 'click' col
    if (hasClickColumn) {
      selectCols += df("click").cast(DoubleType)
      df.select(selectCols: _*)
    } else {
      df.select(selectCols: _*).withColumn("click", lit(0))
    }
  }

  def encodeLabel(unionDf: DataFrame, df1: DataFrame, df2: DataFrame, inputColumn: String): (DataFrame, DataFrame) = {
    println(s"Encoding label $inputColumn")
    val indexer = new StringIndexer()
      .setInputCol(inputColumn)
      .setOutputCol(inputColumn + "_index")
      .fit(unionDf)

    def transform(df: DataFrame) = {
      val indexed = indexer.transform(df)
      val encoder = new OneHotEncoder()
        .setDropLast(false)
        .setInputCol(inputColumn + "_index")
        .setOutputCol(inputColumn + "_vector")

      encoder.transform(indexed)
        .drop(inputColumn)
        .drop(inputColumn + "_index")
    }

    (transform(df1), transform(df2))
  }

  val categoricalColumns = Seq(
    //    "device_id",
    //    "device_ip",
    "device_model",
    "device_type",
    "device_conn_type",
    "time_year",
    "time_month",
    "time_day",
    "time_hour",
    "C1",
    "banner_pos",
    //    "site_id",
    //    "site_domain",
    "site_category",
    //    "app_id",
    "app_domain",
    "app_category",
    "C14",
    "C15",
    "C16",
    "C17",
    "C18",
    "C19",
    "C20",
    "C21")

  val categoricalColumnsVectors = categoricalColumns.map(_ + "_vector")

  def encodeLabels(trainDf: DataFrame, testDf: DataFrame): (DataFrame, DataFrame) = {
    // TODO: ???
    trainDf.cache()
    testDf.cache()

    // add fictive 'click' column to testDf so we can union them
    //    val unionDf = trainDf.unionAll(
    //      testDf.withColumn("click", lit(0))
    //    )

    // remove 'click' column so we can union them correctly
    val unionDf = trainDf.unionAll(testDf)

    unionDf.cache()

    //    println("testDf size is " + testDf.count())
    //    println("trainDf size is " + trainDf.count())
    //    println("unionDf size: " + unionDf.count())
    //
    //    println("testDf: start ======")
    //    testDf.show(100)
    //    println("testDf: end ======")
    //
    //    println("trainDf: start ======")
    //    trainDf.show(100)
    //    println("trainDf: end ======")
    //
    //    println("unionDf: start ======")
    //    unionDf.show(100)
    //    println("unionDf: end ======")
    //
    //    println("uniton print start======")
    //    unionDf.foreach(println)
    //    println("uniton print end======")


    categoricalColumns.foldLeft(trainDf -> testDf) { case ((df1, df2), col) => encodeLabel(unionDf, df1, df2, col) }
  }

  def assembleFeatures(df: DataFrame): DataFrame = {
    val assembler = new VectorAssembler()
      .setInputCols(categoricalColumnsVectors.toArray)
      .setOutputCol("features")

    assembler.transform(df)
  }

  def transformHour(df: DataFrame): DataFrame = {
    val toYear = udf[Int, String](s => DateUtils.parse(s)._1)
    val toMonth = udf[Int, String](s => DateUtils.parse(s)._2)
    val toDay = udf[Int, String](s => DateUtils.parse(s)._3)
    val toHour = udf[Int, String](s => DateUtils.parse(s)._4)

    df.withColumn("time_year", toYear(df("hour")))
      .withColumn("time_month", toMonth(df("hour")))
      .withColumn("time_day", toDay(df("hour")))
      .withColumn("time_hour", toHour(df("hour")))
  }


  def calcMetrics(predictionAndLabels: RDD[(Double, Double)]): Unit = {
    // Instantiate metrics object
    val metrics = new BinaryClassificationMetrics(predictionAndLabels)

    // Precision by threshold
    //    val precision = metrics.precisionByThreshold
    //    precision.foreach { case (t, p) =>
    //      println(s"Threshold: $t, Precision: $p")
    //    }
    //
    //    // Recall by threshold
    //    val recall = metrics.recallByThreshold
    //    recall.foreach { case (t, r) =>
    //      println(s"Threshold: $t, Recall: $r")
    //    }
    //
    //    // Precision-Recall Curve
    //    val PRC = metrics.pr
    //
    //    // F-measure
    //    val f1Score = metrics.fMeasureByThreshold
    //    f1Score.foreach { case (t, f) =>
    //      println(s"Threshold: $t, F-score: $f, Beta = 1")
    //    }
    //
    //    val beta = 0.5
    //    val fScore = metrics.fMeasureByThreshold(beta)
    //    f1Score.foreach { case (t, f) =>
    //      println(s"Threshold: $t, F-score: $f, Beta = 0.5")
    //    }
    //
    //    // AUPRC
    //    val auPRC = metrics.areaUnderPR
    //    println("Area under precision-recall curve = " + auPRC)
    //
    //    // Compute thresholds used in ROC and PR curves
    //    val thresholds = precision.map(_._1)
    //
    //    // ROC Curve
    //    val roc = metrics.roc

    // AUROC
    val auROC = metrics.areaUnderROC
    println("Area under ROC = " + auROC)
  }

  object DateUtils {
    val dateFormat = new ThreadLocal[SimpleDateFormat]() {
      override def initialValue(): SimpleDateFormat = new SimpleDateFormat("yyMMddHH")
    }

    def parse(s: String): (Int, Int, Int, Int) = {
      val date = dateFormat.get().parse(s)
      val cal = Calendar.getInstance()
      cal.setTime(date)
      val year = cal.get(Calendar.YEAR)
      val month = cal.get(Calendar.MONTH)
      val day = cal.get(Calendar.DAY_OF_MONTH)
      val hour = cal.get(Calendar.HOUR_OF_DAY)
      (year, month, day, hour)
    }
  }

}
