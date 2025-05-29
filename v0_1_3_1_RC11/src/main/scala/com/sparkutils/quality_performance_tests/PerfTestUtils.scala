package com.sparkutils.quality_performance_tests
import com.sparkutils.quality
import com.sparkutils.quality.impl.extension.FunNRewrite
import com.sparkutils.quality.ruleRunner
import com.sparkutils.qualityTests.TestUtils
import org.apache.spark.sql.catalyst.optimizer.ConstantFolding
import org.scalameter.api._

object PerfTestUtils extends TestUtils {


  val withRewrite = testPlan(FunNRewrite, secondRunWithoutPlan = false) _
  val withExtraConstantFolding = testPlan(ConstantFolding, secondRunWithoutPlan = false) _

  /**
   * Enable any spark wide optimisations for a given run
   * @param thunk
   */
  def extraPerfOptions(thunk: Unit): Unit =
    withRewrite(
      thunk
    )
  /**
   * Enable any spark wide optimisations for a given run
   * @param thunk
   */
  def rewriteAndFold(thunk: Unit): Unit =
    withRewrite(
      withExtraConstantFolding( // attempt a further constant fold for case statements
        thunk
      )
    )

  trait ExtraPerfTests extends TestTypes.TheRunner with BaseConfig {

    performance of "resultWriting_rc5_specifics" config (
      exec.minWarmupRuns -> 2,
      exec.maxWarmupRuns -> 4,
      exec.benchRuns -> 4,
      exec.jvmcmd -> (System.getProperty("java.home")+"/bin/java"),
      exec.jvmflags -> Args.args // 16GB on github runners
      //  verbose -> true
    ) in {

      quality.registerQualityFunctions()
/*
      measure method "no forceEval in codegen compile evals false - extra config" in {
        _forceCodeGen {
          extraPerfOptions {
            using(rows) afterTests {sparkSession.close()} in evaluate(_.withColumn("quality", ruleRunner(TestData.ruleSuite, forceRunnerEval = false, compileEvals = false)), "no_forceEval_in_codegen_compile_evals_false_extra_config")
          }
        }
      }

      measure method "no forceEval in codegen compile evals false - extra config fold" in {
        _forceCodeGen {
          rewriteAndFold {
            using(rows) afterTests {sparkSession.close()} in evaluate(_.withColumn("quality", ruleRunner(TestData.ruleSuite, forceRunnerEval = false, compileEvals = false)), "no_forceEval_in_codegen_compile_evals_false_extra_config_fold")
          }
        }
      } */
/*
      measure method "no forceEval in codegen compile evals false" in {
        _forceCodeGen {
            using(rows) afterTests {sparkSession.close()} in evaluate(_.withColumn("quality", ruleRunner(TestData.ruleSuite, forceRunnerEval = false, compileEvals = false)), "no_forceEval_in_codegen_compile_evals_false")
        }
      }*/
      /*
            measure method "no forceEval in interpreted compile evals false - extra config" in {
              _forceInterpreted {
                extraPerfOptions {
                  using(rows) afterTests {sparkSession.close()} in evaluate(_.withColumn("quality", ruleRunner(TestData.ruleSuite, forceRunnerEval = false, compileEvals = false)), "no_forceEval_in_interpreted_compile_evals_false_extra_config")
                }
              }
            }*/

      measure method "json no forceEval in codegen compile evals false - extra config fold" in {
        _forceCodeGen {
          rewriteAndFold {
            using(rows) afterTests {sparkSession.close()} in evaluate(_.withColumn("quality", ruleRunner(TestData.jsonRuleSuite, forceRunnerEval = false, compileEvals = false)), "json_no_forceEval_in_codegen_compile_evals_false_extra_config_fold")
          }
        }
      }

      measure method "json no forceEval in codegen compile evals false - extra config" in {
        _forceCodeGen {
          extraPerfOptions {
            using(rows) afterTests {sparkSession.close()} in evaluate(_.withColumn("quality", ruleRunner(TestData.jsonRuleSuite, forceRunnerEval = false, compileEvals = false)), "json_no_forceEval_in_codegen_compile_evals_false_extra_config")
          }
        }
      }

      /*measure method "json no forceEval in interpreted compile evals false - extra config" in {
        _forceInterpreted {
          extraPerfOptions {
            using(rows) afterTests {sparkSession.close()} in evaluate(_.withColumn("quality", ruleRunner(TestData.jsonRuleSuite, forceRunnerEval = false, compileEvals = false)), "json_no_forceEval_in_interpreted_compile_evals_false_extra_config")
          }
        }
      }*/

    }

  }

}
