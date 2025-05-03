package com.sparkutils.quality_performance_tests

import com.sparkutils.quality
import com.sparkutils.quality._
import com.sparkutils.quality.impl.util.ComparableMapConverter
import com.sparkutils.qualityTests.TestUtils
import com.sparkutils.quality_performance_tests.PerfTestUtils.ExtraPerfTests
import com.sparkutils.quality_performance_tests.TestSourceData.{MAXSIZE, STEP, inputsDir}
import org.apache.spark.sql
import org.apache.spark.sql.functions.col
import org.apache.spark.sql.{Column, DataFrame, SaveMode, SparkSession}
import org.scalameter.api._
import org.scalameter.picklers.noPickler.instance

case class TestData(location: String, idPrefix: String, id: Int, page: Long, department: String)

object TestData {
  val dataBasis = Seq(
    TestData("US", "a", 1, 1, "sales"),
    TestData("UK", "a", 1, 2, "marketing"),
    TestData("CH", "a", 1, 3, "hr"),
    TestData("MX", "a", 1, 4, "it"),
    TestData("BR", "a", 1, 5, "ops"),
  )

  val schema = "'location String, idPrefix String, id Int, page Long, department String'"

  def setup(rows: Int, sparkSession: SparkSession) = {
    import sparkSession.implicits._

    sparkSession.range(rows / TestData.dataBasis.size / 26).map{id =>
      (for{
        a <- 'a' to 'z'
        td <- dataBasis
      } yield td.copy(idPrefix = s"$a", id = id.toInt)).toSeq
    }.selectExpr("explode(value) as f").selectExpr("f.*","to_json(f) payload", "f struct_payload")
  }

  // fixed size of rules and complexity to start with (easier to compare with other tooling)
  val ruleSuite = rules("")

  def rules(prefix: String) = RuleSuite(Id(1,1),
    Seq(
      RuleSet(Id(1,1),
        Seq(
          Rule(Id(1,1), ExpressionRule(s"`dq is applicable for`('Non UK') and ${prefix}department = 'sales'")),
          Rule(Id(2,1), ExpressionRule(s"`dq is applicable for`('UK & US') and ${prefix}department = 'marketing'")),
          Rule(Id(3,1), ExpressionRule(s"`dq is applicable for`('Americas') and ${prefix}department = 'hr'")),
          Rule(Id(4,1), ExpressionRule(s"`dq is applicable for`('Europe') and ${prefix}department = 'it'")),
          Rule(Id(5,1), ExpressionRule(s"`isMissing`(${prefix}id) and ${prefix}department = 'ops'")),
          Rule(Id(6,1), ExpressionRule(s"`dq is applicable for`('Non UK') and ${prefix}department = 'marketing'")),
          Rule(Id(7,1), ExpressionRule(s"`dq is applicable for`('UK & US') and ${prefix}department= 'hr'")),
          Rule(Id(8,1), ExpressionRule(s"`dq is applicable for`('Americas') and ${prefix}department = 'it'")),
          Rule(Id(9,1), ExpressionRule(s"`dq is applicable for`('Europe') and ${prefix}department = 'sales'")),
          Rule(Id(10,1), ExpressionRule(s"`isMissing`(${prefix}id) and ${prefix}department = 'sales'")),
          Rule(Id(11,1), ExpressionRule(s"`dq is applicable for`('Non UK') and ${prefix}department = 'it'")),
          Rule(Id(12,1), ExpressionRule(s"`dq is applicable for`('UK & US') and ${prefix}department = 'sales'")),
          Rule(Id(13,1), ExpressionRule(s"`dq is applicable for`('Americas') and ${prefix}department = 'marketing'")),
          Rule(Id(14,1), ExpressionRule(s"`dq is applicable for`('Europe') and ${prefix}department = 'hr'")),
          Rule(Id(15,1), ExpressionRule(s"isMissing(${prefix}id) and ${prefix}department = 'hr'")),
        )
      )
    ),
    lambdaFunctions = Seq(
/*      LambdaFunction("Non UK", "array('US','CH','MX','BR')", Id(1,1)),
      LambdaFunction("UK & US", "array('US','UK')", Id(2,1)),
      LambdaFunction("Americas", "array('US','MX','BR')", Id(3,1)),
      LambdaFunction("Europe", "array('CH')", Id(4,1)),*/
      LambdaFunction(s"dq is applicable for", s"loc -> array_contains(${theCase("loc")}, ${prefix}location)", Id(5,1)),
      LambdaFunction("isMissing", "wh -> if(wh is null, true, regexp_like(wh,'^\\s*$'))", Id(6,1))
    )
  )

  // the dmn does a context / decision table, this should all constant fold but for fairness it's introduced
  def theCase(in: String) =
    s"""
    CASE
        WHEN $in = 'Non UK' THEN array('US','CH','MX','BR')
        WHEN $in = 'UK & US' THEN array('US','UK')
        WHEN $in = 'Americas' THEN array('US','MX','BR')
        WHEN $in = 'Europe' THEN array('CH')
        ELSE array()
    END
      """

  val jsonRuleSuite = {
    val r = rules("jPayload().") // don't have to introduce a new function, but you would
    r.copy(lambdaFunctions = r.lambdaFunctions :+ LambdaFunction("jPayload", s"from_json(payload, $schema)", Id(7,1)))
  }

  def baselineRulesStrings(prefix: String)(implicit sparkSession: SparkSession) =
    Seq(  s"array_contains(${theCase("'Non UK'")}, ${prefix}location) and ${prefix}department = 'sales'",
      s"array_contains(${theCase("'UK & US'")}, ${prefix}location) and ${prefix}department = 'marketing'",
      s"array_contains(${theCase("'Americas'")}, ${prefix}location) and ${prefix}department = 'hr'",
      s"array_contains(${theCase("'Europe'")}, ${prefix}location) and ${prefix}department = 'it'",
      s"if(${prefix}id is null, true, regexp_like(${prefix}id,'^\\s*$$')) and ${prefix}department = 'ops'",
      s"array_contains(${theCase("'Non UK'")}, ${prefix}location) and ${prefix}department = 'marketing'",
      s"array_contains(${theCase("'UK & US'")}, ${prefix}location) and ${prefix}department = 'hr'",
      s"array_contains(${theCase("'Americas'")}, ${prefix}location) and ${prefix}department = 'it'",
      s"array_contains(${theCase("'Europe'")}, ${prefix}location) and ${prefix}department = 'sales'",
      s"if(${prefix}id is null, true, regexp_like(${prefix}id,'^\\s*$$')) and ${prefix}department = 'sales'",
      s"array_contains(${theCase("'Non UK'")}, ${prefix}location) and ${prefix}department = 'it'",
      s"array_contains(${theCase("'UK & US'")}, ${prefix}location) and ${prefix}department = 'sales'",
      s"array_contains(${theCase("'Americas'")}, ${prefix}location) and ${prefix}department = 'marketing'",
      s"array_contains(${theCase("'Europe'")}, ${prefix}location) and ${prefix}department = 'hr'",
      s"if(${prefix}id is null, true, regexp_like(${prefix}id,'^\\s*$$')) and ${prefix}department = 'hr'",
    )


  def baselineRules(prefix: String)(implicit sparkSession: SparkSession) =
    sql.functions.array( baselineRulesStrings(prefix).map(sql.functions.expr) :_* )

  def baselineAudit(col: Column)(implicit sparkSession: SparkSession) = {

    sql.functions.struct(
      sql.functions.array_contains(col, sql.functions.lit(false)).as("overallResult"),
      sql.functions.map(
        sql.functions.lit(1L).as("setId"),
        sql.functions.struct(
          sql.functions.array_contains(col, sql.functions.lit(false)).as("setResult"),
          sql.functions.map(
            baselineRulesStrings("").indices.flatMap(i => Seq(
              sql.functions.lit(i.toLong).as("ruleId"),
              sql.functions.get(col, sql.functions.lit(i + 1)).as("result")
            )) :_*
          ).as("rules")
      )).as("ruleSets"))
  }

  def baseline(implicit sparkSession: SparkSession) = baselineRules("")

  def jsonBaseline(implicit sparkSession: SparkSession) = baselineRules(s"from_json(payload, $schema).")
}

object Args {
  val args = List(
    "-Xmx10g","-Xms10g",// 16GB on github runners, 10gb ok on 21 (12 blows), 12gb fine on jdk 8.
    "-ea",
    "-XX:+IgnoreUnrecognizedVMOptions",
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
    "--add-opens=java.base/java.io=ALL-UNNAMED",
    "--add-opens=java.base/java.net=ALL-UNNAMED",
    "--add-opens=java.base/java.nio=ALL-UNNAMED",
    "--add-opens=java.base/java.util=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.cs=ALL-UNNAMED",
    "--add-opens=java.base/sun.security.action=ALL-UNNAMED",
    "--add-opens=java.base/sun.util.calendar=ALL-UNNAMED"
  )
}

object TestSourceData extends TestUtils {
  val inputsDir = "./target/testInputData"
  // 4 cores on github runners
  val MAXSIZE = 200000 // 10000000  10mil, takes about 1.5 - 2hrs on dev box , 2m only on server is 3hours or so without dmn it's over 6hrs with, doing a single 1m run
  val STEP =    200000

  def main(args: Array[String]): Unit = {

    def setup(params: (Int)): Unit = {
      TestData.setup(params, sparkSession).write.mode(SaveMode.Overwrite).parquet(inputsDir + s"/testInputData_${params}_rows")
      val n = sparkSession.read.parquet(inputsDir + s"/testInputData_${params}_rows").rdd.getNumPartitions
      println(s"wrote $n partitions")
    }

    for{
      i <- STEP to MAXSIZE by STEP
    } {
      println(s"setting up input data $i")
      setup(i)
    }

    sparkSession.close()
  }
}

trait Fwder {

  @transient
  lazy val utils: TestUtils = new TestUtils {}

  // if this blows then debug on CodeGenerator 1294, 1299 and grab code.body
  def _forceCodeGen[T](f: => T): T = utils.forceCodeGen(f)

  def _forceInterpreted[T](f: => T): T = utils.forceInterpreted(f)

  def _outputDir: String = utils.outputDir

  def _sparkSession: SparkSession = utils.sparkSession
}

object TestTypes {
  type TheRunner = Bench.OfflineReport // Bench.OfflineReport //Bench.LocalTime
}

object PerfTests extends TestTypes.TheRunner with PerfTestBase with ExtraPerfTests with Fwder {

}

trait BaseConfig {

  val rows = Gen.range("rowCount")(STEP, MAXSIZE, STEP)

  def _outputDir: String

  implicit def _sparkSession: SparkSession

  def testData(size: Int): DataFrame = TestData.setup(size, _sparkSession).repartition(4)
  //  _sparkSession.read.parquet(inputsDir + s"/testInputData_${size}_rows")

  // dump the file for the row size into a new copy
  def evaluate(fdf: DataFrame => DataFrame, testCase: String)(params: (Int)): Unit = {
    //fdf(testData(params)).write.mode(SaveMode.Overwrite).parquet(_outputDir + s"/testOutputData_${testCase}_${params}_rows")
    val c = fdf(testData(params)).select(ComparableMapConverter(col("quality"))).distinct().count
    println("c"+c) // make sure it's used
  }

  // show counts do not do much
  def evaluateWithCount(fdf: DataFrame => DataFrame, testCase: String)(params: (Int)): Unit = {
    fdf(testData(params)).count
  }

  // show behaviour of a .cache first
  def evaluateWithCacheCount(fdf: DataFrame => DataFrame, testCase: String)(params: (Int)): Unit = {
    val d = fdf(testData(params)).cache
    d.count
  }

  def dumpTime =
    println("Time is " + java.time.LocalTime.now())


  // if this blows then debug on CodeGenerator 1294, 1299 and grab code.body
  def _forceCodeGen[T](f: => T): T

  def _forceInterpreted[T](f: => T): T

  def close(): Unit = _sparkSession.close()
}

trait PerfTestBase extends TestTypes.TheRunner with BaseConfig {

  performance of "resultWriting" config (
    exec.minWarmupRuns -> 2,
    exec.maxWarmupRuns -> 4,
    exec.benchRuns -> 4,
    exec.jvmcmd -> (System.getProperty("java.home")+"/bin/java"),
    exec.jvmflags -> Args.args // 16GB on github runners
    //  verbose -> true
  ) in {

    // force loading
    _sparkSession.conf

    quality.registerQualityFunctions()

    dumpTime
/*
    measure method "copy in codegen" in {
      _forceCodeGen {
        using(rows) afterTests {close()} in evaluate(identity, "copy_codegen")
      }
    }*//*

    measure method "copy in interpreted" in {
      _forceInterpreted {
        using(rows) afterTests {sparkSession.close()} in evaluate(identity, "copy_interpreted")
      }
    }*//*
    measure method "audit baseline in codegen - interim projection" in {
      val spark = _sparkSession
      import spark.implicits._

      _forceCodeGen {
        using(rows) afterTests {
          close()
        } in evaluate(_.withColumn("rr", TestData.baseline).withColumn("quality", TestData.baselineAudit($"rr")), "audit_baseline_codegen")
      }
    }
*//*
    measure method "audit baseline in codegen" in {
      val spark = _sparkSession
      import spark.implicits._

      _forceCodeGen {
        using(rows) afterTests {
          close()
        } in evaluate(_.withColumn("quality", TestData.baselineAudit(TestData.baseline)), "audit_baseline_codegen")
      }
    }*/
/*
    measure method "baseline in codegen" in {
      _forceCodeGen {
        using(rows) afterTests {close()} in evaluate(_.withColumn("quality", TestData.baseline), "baseline_codegen")
      }
    }

    measure method "count baseline in codegen" in {
      _forceCodeGen {
        using(rows) afterTests {
          close()
        } in evaluateWithCount(_.withColumn("quality", TestData.baseline), "baseline_codegen")
      }
    }

    measure method "count audit baseline in codegen - interim projection" in {
      val spark = _sparkSession
      import spark.implicits._

      _forceCodeGen {
        using(rows) afterTests {
          close()
        } in evaluateWithCacheCount(_.withColumn("rr", TestData.baseline).withColumn("quality", TestData.baselineAudit($"rr")), "audit_baseline_codegen")
      }
    }
    measure method "count audit baseline in codegen" in {
      val spark = _sparkSession
      import spark.implicits._

      _forceCodeGen {
        using(rows) afterTests {
          close()
        } in evaluateWithCacheCount(_.withColumn("quality", TestData.baselineAudit(TestData.baseline)), "audit_baseline_codegen")
      }
    }

    measure method "cache count baseline in codegen" in {
      _forceCodeGen {
        using(rows) afterTests {
          close()
        } in evaluateWithCacheCount(_.withColumn("quality", TestData.baseline), "baseline_codegen")
      }
    }

    measure method "cache count audit baseline in codegen - interim projection" in {
      val spark = _sparkSession
      import spark.implicits._

      _forceCodeGen {
        using(rows) afterTests {
          close()
        } in evaluateWithCacheCount(_.withColumn("rr", TestData.baseline).withColumn("quality", TestData.baselineAudit($"rr")), "audit_baseline_codegen")
      }
    }

    measure method "cache count audit baseline in codegen" in {
      val spark = _sparkSession
      import spark.implicits._

      _forceCodeGen {
        using(rows) afterTests {
          close()
        } in evaluateWithCacheCount(_.withColumn("quality", TestData.baselineAudit(TestData.baseline)), "audit_baseline_codegen")
      }
    }*/
    /*

    measure method "baseline in interpreted" in {
      _forceInterpreted {
        using(rows) afterTests {sparkSession.close()} in evaluate(_.withColumn("quality", TestData.baseline), "baseline_interpreted")
      }
    }

    measure method "json audit baseline in codegen" in {
      val spark = _sparkSession
      import spark.implicits._

      _forceCodeGen {
        using(rows) afterTests {
          close()
        } in evaluate(_.withColumn("quality", TestData.baselineAudit(TestData.jsonBaseline)), "json_audit_baseline_codegen")
      }
    }*/
    /*
    measure method "json baseline in codegen" in {
      _forceCodeGen {
        using(rows) afterTests {close()} in evaluate(_.withColumn("quality", TestData.jsonBaseline), "json_baseline_codegen")
      }
    }

    measure method "count json baseline in codegen" in {
      _forceCodeGen {
        using(rows) afterTests {
          close()
        } in evaluateWithCount(_.withColumn("quality", TestData.jsonBaseline), "json_baseline_codegen")
      }
    }*/
    /*

    measure method "json baseline in interpreted" in {
      _forceInterpreted {
        using(rows) afterTests {close()} in evaluate(_.withColumn("quality", TestData.jsonBaseline), "json_baseline_interpreted")
      }
    }


   // the below aren't really that interesting, they perform well on lower row counts but not on higher counts

    measure method "forceEval in codegen" in {
      _forceCodeGen {
        using(rows) in evaluate(_.withColumn("quality", ruleRunner(TestData.ruleSuite, forceRunnerEval = true)), "forceEval_in_codegen")
      }
    }

    measure method "forceEval in interpreted" in {
      _forceInterpreted {
        using(rows) in evaluate(_.withColumn("quality", ruleRunner(TestData.ruleSuite, forceRunnerEval = true)), "forceEval_in_interpreted")
      }
    }

    measure method "forceEval in codegen compileEvals false" in {
      _forceCodeGen {
        using(rows) in evaluate(_.withColumn("quality", ruleRunner(TestData.ruleSuite, forceRunnerEval = true, compileEvals = false)), "forceEval_in_codegen_compile_evals_false")
      }
    }

    measure method "forceEval in interpreted compileEvals false" in {
      _forceInterpreted {
        using(rows) in evaluate(_.withColumn("quality", ruleRunner(TestData.ruleSuite, forceRunnerEval = true, compileEvals = false)), "forceEval_in_interpreted_compile_evals_false")
      }
    }

    measure method "no forceEval in codegen" in {
      _forceCodeGen {
        using(rows) in evaluate(_.withColumn("quality", ruleRunner(TestData.ruleSuite, forceRunnerEval = false)), "no_forceEval_in_codegen")
      }
    }

    measure method "no forceEval in interpreted" in {
      _forceInterpreted {
        using(rows) in evaluate(_.withColumn("quality", ruleRunner(TestData.ruleSuite, forceRunnerEval = false)), "no_forceEval_in_interpreted")
      }
    }
*//*
    measure method "no forceEval in codegen compile evals false" in {
      _forceCodeGen {
        using(rows) afterTests {sparkSession.close()} in evaluate(_.withColumn("quality", ruleRunner(TestData.ruleSuite, forceRunnerEval = false, compileEvals = false)), "no_forceEval_in_codegen_compile_evals_false")
      }
    }

    measure method "no forceEval in interpreted compile evals false" in {
      forceInterpreted {
        using(rows) afterTests {sparkSession.close()} in evaluate(_.withColumn("quality", ruleRunner(TestData.ruleSuite, forceRunnerEval = false, compileEvals = false)), "no_forceEval_in_interpreted_compile_evals_false")
      }
    }
    measure method "json no forceEval in codegen compile evals false" in {
      _forceCodeGen {
        using(rows) afterTests {sparkSession.close()} in evaluate(_.withColumn("quality", ruleRunner(TestData.jsonRuleSuite, forceRunnerEval = false, compileEvals = false)), "json_no_forceEval_in_codegen_compile_evals_false")
      }
    }

    measure method "json no forceEval in interpreted compile evals false" in {
      _forceInterpreted {
        using(rows) afterTests {sparkSession.close()} in evaluate(_.withColumn("quality", ruleRunner(TestData.jsonRuleSuite, forceRunnerEval = false, compileEvals = false)), "json_no_forceEval_in_interpreted_compile_evals_false")
      }
    }*/
  }

}
