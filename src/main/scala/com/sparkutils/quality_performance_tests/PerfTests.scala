package com.sparkutils.quality_performance_tests

import com.sparkutils.quality
import com.sparkutils.quality._
import com.sparkutils.qualityTests.TestUtils
import com.sparkutils.quality_performance_tests.PerfTestUtils.extraPerfOptions
import com.sparkutils.quality_performance_tests.PerfTests.sparkSession
import com.sparkutils.quality_performance_tests.TestSourceData.{MAXSIZE, STEP, inputsDir}
import org.apache.spark.sql
import org.apache.spark.sql.{DataFrame, SaveMode, SparkSession}
import org.scalameter.api._

case class TestData(location: String, idPrefix: String, id: Int, page: Long, department: String)

object TestData {
  val dataBasis = Seq(
    TestData("US", "a", 1, 1, "sales"),
    TestData("UK", "a", 1, 2, "marketing"),
    TestData("CH", "a", 1, 3, "hr"),
    TestData("MX", "a", 1, 4, "it"),
    TestData("BR", "a", 1, 5, "ops"),
  )

  def setup(rows: Int, sparkSession: SparkSession) = {
    import sparkSession.implicits._

    sparkSession.range(rows / TestData.dataBasis.size / 26).map{id =>
      (for{
        a <- 'a' to 'z'
        td <- dataBasis
      } yield td.copy(idPrefix = s"$a", id = id.toInt)).toSeq
    }.selectExpr("explode(value) as f").selectExpr("f.*")
  }

  // fixed size of rules and complexity to start with (easier to compare with other tooling)
  val ruleSuite = RuleSuite(Id(1,1),
    Seq(
      RuleSet(Id(1,1),
        Seq(
          Rule(Id(1,1), ExpressionRule("`dq is applicable for`(`Non UK`()) and department = 'sales'")),
          Rule(Id(2,1), ExpressionRule("`dq is applicable for`(`UK & US`()) and department = 'marketing'")),
          Rule(Id(3,1), ExpressionRule("`dq is applicable for`(`Americas`()) and department = 'hr'")),
          Rule(Id(4,1), ExpressionRule("`dq is applicable for`(`Europe`()) and department = 'it'")),
          Rule(Id(5,1), ExpressionRule("`isMissing`(id) and department = 'ops'")),
          Rule(Id(6,1), ExpressionRule("`dq is applicable for`(`Non UK`()) and department = 'marketing'")),
          Rule(Id(7,1), ExpressionRule("`dq is applicable for`(`UK & US`()) and department = 'hr'")),
          Rule(Id(8,1), ExpressionRule("`dq is applicable for`(`Americas`()) and department = 'it'")),
          Rule(Id(9,1), ExpressionRule("`dq is applicable for`(`Europe`()) and department = 'sales'")),
          Rule(Id(10,1), ExpressionRule("`isMissing`(id) and department = 'sales'")),
          Rule(Id(11,1), ExpressionRule("`dq is applicable for`(`Non UK`()) and department = 'it'")),
          Rule(Id(12,1), ExpressionRule("`dq is applicable for`(`UK & US`()) and department = 'sales'")),
          Rule(Id(13,1), ExpressionRule("`dq is applicable for`(`Americas`()) and department = 'marketing'")),
          Rule(Id(14,1), ExpressionRule("`dq is applicable for`(`Europe`()) and department = 'hr'")),
          Rule(Id(15,1), ExpressionRule("isMissing(id) and department = 'hr'")),
        )
      )
    ),
    lambdaFunctions = Seq(
      LambdaFunction("Non UK", "array('US','CH','MX','BR')", Id(1,1)),
      LambdaFunction("UK & US", "array('US','UK')", Id(2,1)),
      LambdaFunction("Americas", "array('US','MX','BR')", Id(3,1)),
      LambdaFunction("Europe", "array('CH')", Id(4,1)),
      LambdaFunction("dq is applicable for", "loc -> array_contains(loc, location)", Id(5,1)),
      LambdaFunction("isMissing", "wh -> if(wh is null, true, regexp_like(wh,'^\\s*$'))", Id(6,1))
    )
  )

  val baseline = {
    import sparkSession.implicits._

    // the rules above do 16 rules ( 32 'tests' ), so simulating a struct with a bare bones, no lambdas,
    // showing the typical overhead of Quality usage itself
    sql.functions.struct(
      sql.functions.expr("array_contains(array('US','CH','MX','BR'), location) and department = 'sales'"),
      sql.functions.expr("array_contains(array('US','UK'), location) and department = 'marketing'"),
      sql.functions.expr("array_contains(array('US','MX','BR'), location) and department = 'hr'"),
      sql.functions.expr("array_contains(array('CH'), location) and department = 'it'"),
      sql.functions.expr("if(id is null, true, regexp_like(id,'^\\s*$')) and department = 'ops'"),
      sql.functions.expr("array_contains(array('US','CH','MX','BR'), location) and department = 'marketing'"),
      sql.functions.expr("array_contains(array('US','UK'), location) and department = 'hr'"),
      sql.functions.expr("array_contains(array('US','MX','BR'), location) and department = 'it'"),
      sql.functions.expr("array_contains(array('CH'), location) and department = 'sales'"),
      sql.functions.expr("if(id is null, true, regexp_like(id,'^\\s*$')) and department = 'sales'"),
      sql.functions.expr("array_contains(array('US','CH','MX','BR'), location) and department = 'it'"),
      sql.functions.expr("array_contains(array('US','UK'), location) and department = 'sales'"),
      sql.functions.expr("array_contains(array('US','MX','BR'), location) and department = 'marketing'"),
      sql.functions.expr("array_contains(array('CH'), location) and department = 'hr'"),
      sql.functions.expr("if(id is null, true, regexp_like(id,'^\\s*$')) and department = 'hr'"),
    )
  }
}

object TestSourceData extends TestUtils {
  val inputsDir = "./target/testInputData"

  val MAXSIZE = 100000 // 10000000  10mil, takes about 1.5 - 2hrs on dev box
  val STEP =    100000

  def main(args: Array[String]): Unit = {

    def setup(params: (Int)): Unit = {
      TestData.setup(params, sparkSession).write.mode(SaveMode.Overwrite).parquet(inputsDir + s"/testInputData_${params}_rows")
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

object PerfTests extends Bench.OfflineReport with TestUtils {

  performance of "resultWriting" config (
    exec.minWarmupRuns -> 2,
    exec.maxWarmupRuns -> 4,
    exec.benchRuns -> 4,
    exec.jvmcmd -> (System.getProperty("java.home")+"/bin/java"),
    exec.jvmflags -> List("-Xmx24g","-Xms24g")
    //  verbose -> true
  ) in {

    val rows = Gen.range("rowCount")(STEP, MAXSIZE, STEP)

    quality.registerQualityFunctions()

    println("Time is "+java.time.LocalTime.now())

    // dump the file for the row size into a new copy
    def evaluate(fdf: DataFrame => DataFrame, testCase: String)(params: (Int)): Unit = {
      fdf(sparkSession.read.parquet(inputsDir + s"/testInputData_${params}_rows")).write.mode(SaveMode.Overwrite).parquet(outputDir + s"/testOutputData_${testCase}_${params}_rows")
    }

    measure method "copy in codegen" in {
      forceCodeGen {
        using(rows) afterTests {sparkSession.close()} in evaluate(identity, "copy_codegen")
      }
    }

    measure method "copy in interpreted" in {
      forceInterpreted {
        using(rows) afterTests {sparkSession.close()} in evaluate(identity, "copy_interpreted")
      }
    }

    measure method "baseline in codegen" in {
      forceCodeGen {
        using(rows) afterTests {sparkSession.close()} in evaluate(_.withColumn("quality", TestData.baseline), "baseline_codegen")
      }
    }

    measure method "baseline in interpreted" in {
      forceInterpreted {
        using(rows) afterTests {sparkSession.close()} in evaluate(_.withColumn("quality", TestData.baseline), "baseline_interpreted")
      }
    }/*

   // the below aren't really that interesting, they perform well on lower row counts but not on higher counts

    measure method "forceEval in codegen" in {
      forceCodeGen {
        using(rows) in evaluate(_.withColumn("quality", ruleRunner(TestData.ruleSuite, forceRunnerEval = true)), "forceEval_in_codegen")
      }
    }

    measure method "forceEval in interpreted" in {
      forceInterpreted {
        using(rows) in evaluate(_.withColumn("quality", ruleRunner(TestData.ruleSuite, forceRunnerEval = true)), "forceEval_in_interpreted")
      }
    }

    measure method "forceEval in codegen compileEvals false" in {
      forceCodeGen {
        using(rows) in evaluate(_.withColumn("quality", ruleRunner(TestData.ruleSuite, forceRunnerEval = true, compileEvals = false)), "forceEval_in_codegen_compile_evals_false")
      }
    }

    measure method "forceEval in interpreted compileEvals false" in {
      forceInterpreted {
        using(rows) in evaluate(_.withColumn("quality", ruleRunner(TestData.ruleSuite, forceRunnerEval = true, compileEvals = false)), "forceEval_in_interpreted_compile_evals_false")
      }
    }

    measure method "no forceEval in codegen" in {
      forceCodeGen {
        using(rows) in evaluate(_.withColumn("quality", ruleRunner(TestData.ruleSuite, forceRunnerEval = false)), "no_forceEval_in_codegen")
      }
    }

    measure method "no forceEval in interpreted" in {
      forceInterpreted {
        using(rows) in evaluate(_.withColumn("quality", ruleRunner(TestData.ruleSuite, forceRunnerEval = false)), "no_forceEval_in_interpreted")
      }
    }
*/
    measure method "no forceEval in codegen compile evals false" in {
      forceCodeGen {
        using(rows) afterTests {sparkSession.close()} in evaluate(_.withColumn("quality", ruleRunner(TestData.ruleSuite, forceRunnerEval = false, compileEvals = false)), "no_forceEval_in_codegen_compile_evals_false")
      }
    }

    measure method "no forceEval in interpreted compile evals false" in {
      forceInterpreted {
        using(rows) afterTests {sparkSession.close()} in evaluate(_.withColumn("quality", ruleRunner(TestData.ruleSuite, forceRunnerEval = false, compileEvals = false)), "no_forceEval_in_interpreted_compile_evals_false")
      }
    }

    measure method "no forceEval in codegen compile evals false - extra config" in {
      forceCodeGen {
        extraPerfOptions {
          using(rows) afterTests {sparkSession.close()} in evaluate(_.withColumn("quality", ruleRunner(TestData.ruleSuite, forceRunnerEval = false, compileEvals = false)), "no_forceEval_in_codegen_compile_evals_false_extra_config")
        }
      }
    }
    measure method "no forceEval in interpreted compile evals false - extra config" in {
      forceInterpreted {
        extraPerfOptions {
          using(rows) afterTests {sparkSession.close()} in evaluate(_.withColumn("quality", ruleRunner(TestData.ruleSuite, forceRunnerEval = false, compileEvals = false)), "no_forceEval_in_interpreted_compile_evals_false_extra_config")
        }
      }
    }

  }

}
