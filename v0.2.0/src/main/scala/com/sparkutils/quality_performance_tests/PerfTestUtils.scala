package com.sparkutils.quality_performance_tests
import com.sparkutils.quality
import com.sparkutils.quality.impl.extension.FunNRewrite
import com.sparkutils.quality.ruleRunner
import com.sparkutils.qualityTests.util.ClassicSharedTests
import com.sparkutils.testing.ConnectionType
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.optimizer.ConstantFolding
import org.scalameter.api._

object PerfTestUtils extends ClassicSharedTests {

  override val runWith: ConnectionType = com.sparkutils.testing.ClassicOnly

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

  trait ExtraPerfTests extends TestTypes.TheRunner with BaseConfig {
    /**
     * Enable any spark wide optimisations for a given run
     * @param thunk
     */
    def rewriteAndFold[T](thunk: => T): T = {
      var t : T = null.asInstanceOf[T]
      SparkSession.setActiveSession(_sparkSession)

      withRewrite(
        withExtraConstantFolding { // attempt a further constant fold for case statements
          t = thunk

          ()
        }
      )
      t
    }

    performance of "resultWriting_rc7_specifics" config (
      exec.minWarmupRuns -> 2,
      exec.maxWarmupRuns -> 4,
      exec.benchRuns -> 4,
      exec.jvmcmd -> (System.getProperty("java.home")+"/bin/java"),
      exec.jvmflags -> Args.args // 16GB on github runners
      //  verbose -> true
    ) in {

      quality.registerQualityFunctions()

      measure method "quality rewriteAndFold" in {
        _forceCodeGen {
          using(rows) afterTests { close() } in evaluate(rewriteAndFold, _.withColumn("quality", ruleRunner(TestData.ruleSuite) ), "rewriteAndFold")
        }
      }
      measure method "quality no rewrite" in {
        _forceCodeGen {
          using(rows) afterTests { close() } in evaluate(evalIdentity, _.withColumn("quality", ruleRunner(TestData.ruleSuite)), "norewrite")
        }
      }
    }

  }

}
