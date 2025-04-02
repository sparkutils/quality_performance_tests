package com.sparkutils.quality_performance_tests
import com.sparkutils.quality
import com.sparkutils.quality.impl.extension.FunNRewrite
import com.sparkutils.quality.ruleRunner
import com.sparkutils.qualityTests.TestUtils
import org.scalameter.api._
import org.kie.kogito.app._
import scala.collection.JavaConverters._

object PerfTestUtils extends TestUtils {


  val withRewrite = testPlan(FunNRewrite, secondRunWithoutPlan = false) _

  val ns = "https: //kie.org/dmn/_2A8FD60D-746C-4A21-AF7B-2D2D92A33E77"
  val models = new DecisionModels(new Application()).getDecisionModel(ns, "evaluate")

  def main(args: Array[String]): Unit = {//TestData(location: String, idPrefix: String, id: Int, page: Long, department: String)
    val ctx = models.newContext(Map[String, Any]("location" -> "UK", "idPrefix" -> "prefix", "id" -> 2, "page" -> 1L, "department" -> "sales").asJava.asInstanceOf[java.util.Map[String, Object]])

    val res = models.evaluateAll(ctx)

    println(res)
  }

  /**
   * Enable any spark wide optimisations for a given run
   * @param thunk
   */
  def extraPerfOptions(thunk: Unit): Unit =
    withRewrite(thunk)

  trait ExtraPerfTests extends Bench.OfflineReport with BaseConfig {

    performance of "resultWriting_dmn_and_rc5_specifics" config (
      exec.minWarmupRuns -> 2,
      exec.maxWarmupRuns -> 4,
      exec.benchRuns -> 4,
      exec.jvmcmd -> (System.getProperty("java.home")+"/bin/java"),
      exec.jvmflags -> List("-Xmx12g","-Xms12g") // 16GB on github runners
      //  verbose -> true
    ) in {

      quality.registerQualityFunctions()

      // the non json are in for a reference on how it would normally look with direct field usage
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

      measure method "json no forceEval in codegen compile evals false - extra config" in {
        forceCodeGen {
          extraPerfOptions {
            using(rows) afterTests {sparkSession.close()} in evaluate(_.withColumn("quality", ruleRunner(TestData.jsonRuleSuite, forceRunnerEval = false, compileEvals = false)), "json_no_forceEval_in_codegen_compile_evals_false_extra_config")
          }
        }
      }

      measure method "json no forceEval in interpreted compile evals false - extra config" in {
        forceInterpreted {
          extraPerfOptions {
            using(rows) afterTests {sparkSession.close()} in evaluate(_.withColumn("quality", ruleRunner(TestData.jsonRuleSuite, forceRunnerEval = false, compileEvals = false)), "json_no_forceEval_in_interpreted_compile_evals_false_extra_config")
          }
        }
      }

    }

  }

}
