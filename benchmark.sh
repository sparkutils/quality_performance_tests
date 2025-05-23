#!/bin/bash
export MAVEN_OPTS="-ea -XX:+IgnoreUnrecognizedVMOptions -Dhttp.keepAlive=false -Dmaven.wagon.http.pool=false -Dmaven.wagon.http.retryHandler.class=standard -Dmaven.wagon.http.retryHandler.count=3 -Dhttps.protocols=TLSv1.2 -Dorg.slf4j.simpleLogger.log.org.apache.maven.cli.transfer.Slf4jMavenTransferListener=WARN -Dorg.slf4j.simpleLogger.showDateTime=true -Djava.awt.headless=true --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.lang.invoke=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/java.io=ALL-UNNAMED --add-opens java.base/java.net=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.util.concurrent=ALL-UNNAMED --add-opens java.base/java.util.concurrent.atomic=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/sun.nio.cs=ALL-UNNAMED --add-opens java.base/sun.security.action=ALL-UNNAMED --add-opens java.base/sun.util.calendar=ALL-UNNAMED"
echo "----> setting up source data got params " "$@"
mvn exec:java -Dexec.mainClass="com.sparkutils.quality_performance_tests.TestSourceData" -P "$@"
echo "----> running benchmarks"
mvn exec:java -Dexec.mainClass="com.sparkutils.quality_performance_tests.PerfTests" -P "$@"
echo "finished test, the last return is a win - setting up gh-pages"
git checkout gh-pages
git pull
cp -r target/benchmarks/report .
git add -f report/\*
git commit -m "report adding from $@"
git push