package com.sparkutils.quality_performance_tests
import com.sparkutils.quality.impl.extension.FunNRewrite
import com.sparkutils.qualityTests.TestUtils

object PerfTestUtils extends TestUtils {


  val withRewrite = testPlan(FunNRewrite, secondRunWithoutPlan = false) _

  /**
   * Enable any spark wide optimisations for a given run
   * @param thunk
   */
  def extraPerfOptions(thunk: Unit): Unit =
    withRewrite(thunk)
}
