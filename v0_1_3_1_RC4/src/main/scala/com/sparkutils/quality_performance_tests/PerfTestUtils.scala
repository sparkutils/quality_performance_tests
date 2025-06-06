package com.sparkutils.quality_performance_tests

import com.sparkutils.qualityTests.TestUtils

object PerfTestUtils extends TestUtils {

  trait ExtraPerfTests extends TestTypes.TheRunner with BaseConfig {

    performance of "resultWriting_dmn_and_rc5_specifics" config (
      exec.minWarmupRuns -> 2,
      exec.maxWarmupRuns -> 4,
      exec.benchRuns -> 4,
      exec.jvmcmd -> (System.getProperty("java.home")+"/bin/java"),
      exec.jvmflags -> Args.args  // 16GB on github runners
      //  verbose -> true
    ) in {

    }

  }

}
