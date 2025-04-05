package com.sparkutils.quality_performance_tests
import sparkutilsKogito.com.fasterxml.jackson.databind.{ObjectMapper, SerializationFeature}
import sparkutilsKogito.com.fasterxml.jackson.databind.module.SimpleModule
import com.sparkutils.quality
import com.sparkutils.quality.impl.extension.FunNRewrite
import com.sparkutils.quality.ruleRunner
import com.sparkutils.qualityTests.TestUtils
import org.apache.spark.sql.ShimUtils.{column, expression}
import org.apache.spark.sql.catalyst.expressions.codegen.CodegenFallback
import org.apache.spark.sql.catalyst.expressions.{Expression, UnaryExpression}
import org.apache.spark.sql.catalyst.util.{ArrayData, GenericArrayData}
import org.apache.spark.sql.functions.{col, udf}
import org.apache.spark.sql.types.{ArrayType, BooleanType, DataType}
import org.apache.spark.unsafe.types.UTF8String
import org.kie.dmn.feel.lang.types.impl.ComparablePeriod
import org.scalameter.api._
import org.kie.kogito.app._
import org.kie.kogito.dmn.rest.DMNFEELComparablePeriodSerializer
import org.slf4j.{Logger, LoggerFactory}

import java.io.{ByteArrayInputStream, InputStreamReader}
import java.nio.charset.StandardCharsets
import java.util
import scala.collection.JavaConverters._

object PerfTestUtils extends TestUtils {
  // to see startup drools issues the logger is controlled via spark.  trace shows the error about importing is not real, as per https://www.ibm.com/mysupport/s/defect/aCIKe000000CkpOOAS/dt421845?language=en_US
  // as it then later shows: ImportDMNResolverUtil: DMN Model with name=decisions and namespace=decisions successfully imported a DMN with namespace=common name=common locationURI=common.dmn, modelName=null
  //sparkSession.sparkContext.setLogLevel("trace")
  val kieServices = org.kie.api.KieServices.Factory.get()

  val kieContainer = kieServices.getKieClasspathContainer()

  val dmnRuntime = //kieContainer.newKieSession().getKieRuntime(classOf[org.kie.dmn.api.core.DMNRuntime])
    org.kie.api.runtime.KieRuntimeFactory.of(kieContainer.getKieBase())
    .get(classOf[org.kie.dmn.api.core.DMNRuntime])

  val withRewrite = testPlan(FunNRewrite, secondRunWithoutPlan = false) _

  val ns = "decisions"
  val models = dmnRuntime.getModel(ns,ns) //new DecisionModels(new Application()).getDecisionModel(ns, ns)

  val mapper = new ObjectMapper()
    .registerModule(new SimpleModule()
      .addSerializer(classOf[ComparablePeriod], new DMNFEELComparablePeriodSerializer()))
    .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)

  val dmnUDF = udf[Seq[Boolean], String] { json =>
    // assuming it's quicker than using classes
    val testData = mapper.readValue(json, classOf[java.util.Map[String, Object]])

    val ctx = dmnRuntime.newContext() //models.newContext(Map[String, Any]("testData" -> testData).asJava.asInstanceOf[java.util.Map[String, Object]])

    ctx.set("testData", testData)
    
    val res = dmnRuntime.evaluateAll(models, ctx)
    if (res.getDecisionResults.getFirst.hasErrors)
      null
    else
      res.getDecisionResults.getFirst.getResult.asInstanceOf[util.ArrayList[Boolean]].asScala.toSeq
  }

  case class DMNExpression(child: Expression) extends UnaryExpression with CodegenFallback {

    @transient
    lazy val ctx = dmnRuntime.newContext() // the example pages show context outside of loops, we can re share it for a partition

    override def dataType: DataType = ArrayType(BooleanType)

    override def nullSafeEval(input: Any): Any = {
      val i = input.asInstanceOf[UTF8String]
      val bb = i.getByteBuffer
      assert(bb.hasArray)

      val bain = new ByteArrayInputStream(
        bb.array(), bb.arrayOffset() + bb.position(), bb.remaining())

      val str = new InputStreamReader(bain, StandardCharsets.UTF_8)

      // assuming it's quicker than using classes
      val testData = mapper.readValue(str, classOf[java.util.Map[String, Object]])

      ctx.set("testData", testData)

      val res = dmnRuntime.evaluateAll(models, ctx)
      val out =
        if (res.getDecisionResults.getFirst.hasErrors)
          null
        else
          res.getDecisionResults.getFirst.getResult.asInstanceOf[util.ArrayList[Boolean]].toArray
      new GenericArrayData(out)
    }

    override protected def withNewChildInternal(newChild: Expression): Expression = copy(child = newChild)
  }


  // org.drools.compiler.kie.builder.impl.ClasspathKieProject

  /*
  val dmnUDF = udf[Seq[Boolean], String] { json =>
    // assuming it's quicker than using classes
    val testData = mapper.readValue(json, classOf[java.util.Map[String, Object]])

    val ctx = models.newContext(Map[String, Any]("testData" -> testData).asJava.asInstanceOf[java.util.Map[String, Object]])

    val res = models.evaluateAll(ctx)
    if (res.getDecisionResults.getFirst.hasErrors)
      null
    else
      res.getDecisionResults.getFirst.getResult.asInstanceOf[util.ArrayList[Boolean]].asScala.toSeq
  }
*/
  def main(args: Array[String]): Unit = {//TestData(location: String, idPrefix: String, id: Int, page: Long, department: String)
    //val testData = Map[String, Any]("location" -> "UK", "idPrefix" -> "prefix", "id" -> 2, "page" -> 1L, "department" -> "marketing").asJava.asInstanceOf[java.util.Map[String, Object]]
    val json =
      """{
        "location": "UK",
        "idPrefix": "prefix",
        "id": 2,
        "page": 1,
        "department": "marketing"
        }"""

    // to see any logs by kogito during running, shows nothing though at debug or trace
    // sparkSession.sparkContext.setLogLevel("trace")
    val testData = mapper.readValue(json, classOf[java.util.Map[String, Object]])
    /*
        val ctx = models.newContext(Map[String, Any]("testData" -> testData).asJava.asInstanceOf[java.util.Map[String, Object]])

        val res = models.evaluateAll(ctx)

        println(res) */

    val ctx = dmnRuntime.newContext() //models.newContext(Map[String, Any]("testData" -> testData).asJava.asInstanceOf[java.util.Map[String, Object]])

    ctx.set("testData", testData)

    val res = dmnRuntime.evaluateAll(models, ctx)
    if (res.getDecisionResults.getFirst.hasErrors)
      null
    else
      res.getDecisionResults.getFirst.getResult.asInstanceOf[util.ArrayList[Boolean]].asScala.toSeq
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
      exec.jvmflags -> Args.args // 16GB on github runners
      //  verbose -> true
    ) in {

      quality.registerQualityFunctions()

      // expression is 1% faster over 1m with no compilation so the udf isn't run
      measure method "json dmn codegen - expression" in {
        forceCodeGen {
          using(rows) afterTests {close()} in evaluate(_.withColumn("quality", column(DMNExpression(expression(col("payload"))))), "json_dmn_codegen_expression")
        }
      }
/**/
      measure method "json dmn codegen" in {
        forceCodeGen {
          using(rows) afterTests {close()} in evaluate(_.withColumn("quality", dmnUDF(col("payload"))), "json_dmn_codegen")
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
/*
      measure method "no forceEval in interpreted compile evals false - extra config" in {
        forceInterpreted {
          extraPerfOptions {
            using(rows) afterTests {close()} in evaluate(_.withColumn("quality", ruleRunner(TestData.ruleSuite, forceRunnerEval = false, compileEvals = false)), "no_forceEval_in_interpreted_compile_evals_false_extra_config")
          }
        }
      }*/
/*
      measure method "json no forceEval in codegen compile evals false - extra config" in {
        forceCodeGen {
          extraPerfOptions {
            using(rows) afterTests {close()} in evaluate(_.withColumn("quality", ruleRunner(TestData.jsonRuleSuite, forceRunnerEval = false, compileEvals = false)), "json_no_forceEval_in_codegen_compile_evals_false_extra_config")
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
