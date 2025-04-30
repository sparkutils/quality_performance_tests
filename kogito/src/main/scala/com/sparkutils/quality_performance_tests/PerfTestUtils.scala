package com.sparkutils.quality_performance_tests
import com.sparkutils.quality
import com.sparkutils.quality.impl.extension.FunNRewrite
import com.sparkutils.quality.ruleRunner
import com.sparkutils.qualityTests.TestUtils
import org.apache.log4j.{Level, LogManager}
import org.apache.spark.sql.ShimUtils.{column, expression}
import org.apache.spark.sql.catalyst.optimizer.ConstantFolding
import org.apache.spark.sql.functions.col
import com.sparkutils.dmn._
import org.scalameter.api._

import scala.collection.immutable.Seq

object PerfTestUtils extends TestUtils {
  {
    // disable spurious error when dyn loading, happens once or twice when using scaffolding as well
    val ctx = LogManager.getLogger("org.kie.dmn.core.compiler.ImportDMNResolverUtil")
    ctx.setLevel(Level.OFF)
  }

  // to see startup drools issues the logger is controlled via spark.  trace shows the error about importing is not real, as per https://www.ibm.com/mysupport/s/defect/aCIKe000000CkpOOAS/dt421845?language=en_US
  // as it then later shows: ImportDMNResolverUtil: DMN Model with name=decisions and namespace=decisions successfully imported a DMN with namespace=common name=common locationURI=common.dmn, modelName=null
  //sparkSession.sparkContext.setLogLevel("trace")

  val withRewrite = testPlan(FunNRewrite, secondRunWithoutPlan = false) _

  val ns = "decisions"

  val dmnFiles = Seq(
    DMNFile("common.dmn",
      this.getClass.getClassLoader.getResourceAsStream("common.dmn").readAllBytes()
    ),
    DMNFile("decisions.dmn",
      this.getClass.getClassLoader.getResourceAsStream("decisions.dmn").readAllBytes()
    )
  )
  val dmnModel = DMNModelService(ns, ns, Some("DQService"), "JSON")//"struct<evaluate: array<boolean>>")
  val execJson = DMNExecution(dmnFiles, dmnModel,
    Seq(DMNInputField("payload", "JSON", "testData")), DMNConfiguration(""))

  val execStruct = DMNExecution(dmnFiles, dmnModel,
    Seq(DMNInputField("location", "String", "testData.location"),
      DMNInputField("idPrefix", "String", "testData.idPrefix"),
      DMNInputField("id", "Int", "testData.id"),
      DMNInputField("page", "Long", "testData.page"),
      DMNInputField("department", "String", "testData.department")
//DMNInputField("struct_payload", "struct<location: String, idPrefix: String, id: Int, page: Long, department: String>", "testData")
    ), DMNConfiguration(""))

  /**
   * Enable any spark wide optimisations for a given run
   * @param thunk
   */
  def extraPerfOptions(thunk: Unit): Unit =
    withRewrite(thunk)

  val withExtraConstantFolding = testPlan(ConstantFolding, secondRunWithoutPlan = false) _
  def rewriteAndFold(thunk: Unit): Unit =
    withRewrite(
      withExtraConstantFolding( // attempt a further constant fold for case statements
        thunk
      )
    )

  trait ExtraPerfTests extends TestTypes.TheRunner with BaseConfig {

    performance of "resultWriting_dmn_and_rc5_specifics" config (
      exec.minWarmupRuns -> 2,
      exec.maxWarmupRuns -> 4,
      exec.benchRuns -> 4,
      exec.jvmcmd -> (System.getProperty("java.home")+"/bin/java"),
      exec.jvmflags -> Args.args // 16GB on github runners
      //  verbose -> true
    ) in {

      quality.registerQualityFunctions()

      val dmnFiles = Seq(
        DMNFile("common.dmn",
          this.getClass.getClassLoader.getResourceAsStream("common.dmn").readAllBytes()
        ),
        DMNFile("decisions.dmn",
          this.getClass.getClassLoader.getResourceAsStream("decisions.dmn").readAllBytes()
        )
      )
/*
      measure method "json in dmn codegen - decision service" in {
        forceCodeGen {
          using(rows) afterTests {close()} in evaluate(_.withColumn("quality", DMN.dmnEval(execJson)), "json_in_dmn_codegen_decision_service")
        }
      }*/

      measure method "json in dmn interpreted - evaluate all" in {
        forceInterpreted {
          using(rows) afterTests {close()} in evaluate(_.withColumn("quality", DMN.dmnEval(execJson.copy(model = execJson.model.copy(service = None)))), "json_in_dmn_interpreted_evaluate_all")
        }
      }

      measure method "json in dmn codegen - evaluate all" in {
        forceCodeGen {
          using(rows) afterTests {close()} in evaluate(_.withColumn("quality", DMN.dmnEval(execJson.copy(model = execJson.model.copy(service = None)))), "json_in_dmn_codegen_evaluate_all")
        }
      }
/*
      measure method "struct in dmn codegen - decision service" in {
        forceCodeGen {
          using(rows) afterTests {close()} in evaluate(_.withColumn("quality", DMN.dmnEval(execStruct)), "struct_in_dmn_codegen_decision_service")
        }
      } */

      measure method "struct in dmn interpreted - evaluate all" in {
        forceInterpreted {
          using(rows) afterTests {close()} in evaluate(_.withColumn("quality", DMN.dmnEval(execStruct.copy(model = execStruct.model.copy(service = None)))), "struct_in_dmn_interpreted_evaluate_all")
        }
      }

      measure method "struct in dmn codegen - evaluate all" in {
        forceCodeGen {
          using(rows) afterTests {close()} in evaluate(_.withColumn("quality", DMN.dmnEval(execStruct.copy(model = execStruct.model.copy(service = None)))), "struct_in_dmn_codegen_evaluate_all")
        }
      }
      /*

      measure method "json dmn interpreted" in {
        forceInterpreted {
          using(rows) afterTests {close()} in evaluate(_.withColumn("quality", dmnUDF(col("payload"))), "json_dmn_interpreted")
        }
      }
*//*
      measure method "no forceEval in codegen compile evals false - extra config" in {
        forceCodeGen {
          extraPerfOptions {
            using(rows) afterTests {close()} in evaluate(_.withColumn("quality", ruleRunner(TestData.ruleSuite, forceRunnerEval = false, compileEvals = false)), "no_forceEval_in_codegen_compile_evals_false_extra_config")
          }
        }
      }*/

      measure method "no forceEval in interpreted compile evals false - extra config fold" in {
        forceInterpreted {
          rewriteAndFold {
            using(rows) afterTests {close()} in evaluate(_.withColumn("quality", ruleRunner(TestData.ruleSuite, forceRunnerEval = false, compileEvals = false)), "no_forceEval_in_interpreted_compile_evals_false_extra_config_fold")
          }
        }
      }
      /*
            measure method "json no forceEval in codegen compile evals false - extra config" in {
              forceCodeGen {
                extraPerfOptions {
                  using(rows) afterTests {close()} in evaluate(_.withColumn("quality", ruleRunner(TestData.jsonRuleSuite, forceRunnerEval = false, compileEvals = false)), "json_no_forceEval_in_codegen_compile_evals_false_extra_config")
                }
              }
            }*/

      /*measure method "json no forceEval in codegen compile evals false - extra config fold" in {
        forceCodeGen {
          rewriteAndFold {
            using(rows) afterTests {close()} in evaluate(_.withColumn("quality", ruleRunner(TestData.jsonRuleSuite, forceRunnerEval = false, compileEvals = false)), "json_no_forceEval_in_codegen_compile_evals_false_extra_config_fold")
          }
        }
      } */

      /*measure method "count json no forceEval in codegen compile evals false - extra config" in {
        forceCodeGen {
          extraPerfOptions {
            using(rows) afterTests {
              close()
            } in evaluateWithCount(_.withColumn("quality", ruleRunner(TestData.jsonRuleSuite, forceRunnerEval = false, compileEvals = false)), "json_no_forceEval_in_codegen_compile_evals_false_extra_config")
          }
        }
      }

      measure method "cache count json no forceEval in codegen compile evals false - extra config" in {
        forceCodeGen {
          extraPerfOptions {
            using(rows) afterTests {
              close()
            } in evaluateWithCacheCount(_.withColumn("quality", ruleRunner(TestData.jsonRuleSuite, forceRunnerEval = false, compileEvals = false)), "json_no_forceEval_in_codegen_compile_evals_false_extra_config")
          }
        }
      }*/
/*
      measure method "json no forceEval in interpreted compile evals false - extra config" in {
        forceInterpreted {
          extraPerfOptions {
            using(rows) afterTests {close()} in evaluate(_.withColumn("quality", ruleRunner(TestData.jsonRuleSuite, forceRunnerEval = false, compileEvals = false)), "json_no_forceEval_in_interpreted_compile_evals_false_extra_config")
          }
        }
      }
*/
    }

  }

}
