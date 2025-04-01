#!/bin/bash
echo "----> setting up source data got params " "$@"
mvn exec:java -Dexec.mainClass="com.sparkutils.quality_performance_tests.TestSourceData" -P "$@"
echo "----> running benchmarks"
mvn exec:java -Dexec.mainClass="com.sparkutils.quality_performance_tests.PerfTests" -P "$@"
echo "finished test, the last return is a win"