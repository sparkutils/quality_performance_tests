package com.sparkutils.quality_performance_tests

import com.sparkutils.qualityTests.TestUtils

object PerfTestUtils extends TestUtils {

  /**
   * Enable any spark wide optimisations for a given run
   * @param thunk
   */
  def extraPerfOptions(thunk: Unit): Unit = thunk
}
