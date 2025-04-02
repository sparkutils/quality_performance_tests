package com.sparkutils.quality_performance_tests

import com.sparkutils.qualityTests.TestUtils

object PerfTestUtils extends TestUtils {

  trait ExtraPerfTests extends Bench.OfflineReport with BaseConfig {

    performance of "resultWriting_dmn_and_rc5_specifics" config (
      exec.minWarmupRuns -> 2,
      exec.maxWarmupRuns -> 4,
      exec.benchRuns -> 4,
      exec.jvmcmd -> (System.getProperty("java.home")+"/bin/java"),
      exec.jvmflags -> List("-Xmx12g","-Xms12g") // 16GB on github runners
      //  verbose -> true
    ) in {

    }

  }

}
