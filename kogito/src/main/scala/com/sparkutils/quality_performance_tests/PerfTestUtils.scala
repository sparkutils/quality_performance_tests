package com.sparkutils.quality_performance_tests
import sparkutilsKogito.com.fasterxml.jackson.databind.{ObjectMapper, SerializationFeature}
import sparkutilsKogito.com.fasterxml.jackson.databind.module.SimpleModule
import com.sparkutils.quality
import com.sparkutils.quality.impl.extension.FunNRewrite
import com.sparkutils.quality.ruleRunner
import com.sparkutils.qualityTests.TestUtils
import org.apache.log4j.{Level, LogManager}
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.config.LoggerConfig
import org.apache.spark.sql.ShimUtils.{column, expression}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.codegen.CodegenFallback
import org.apache.spark.sql.catalyst.expressions.{Expression, UnaryExpression}
import org.apache.spark.sql.catalyst.optimizer.ConstantFolding
import org.apache.spark.sql.catalyst.util.GenericArrayData
import org.apache.spark.sql.functions.{col, udf}
import org.apache.spark.sql.types.{ArrayType, BooleanType, DataType, ObjectType}
import org.apache.spark.unsafe.types.UTF8String
import org.kie.dmn.api.core.{DMNContext, DMNResult}
import org.kie.dmn.core.internal.utils.DMNRuntimeBuilder
import org.kie.dmn.feel.lang.types.impl.ComparablePeriod
import org.kie.internal.io.ResourceFactory
import org.scalameter.api._
import org.kie.kogito.dmn.rest.DMNFEELComparablePeriodSerializer
import org.slf4j.{Logger, LoggerFactory}

import java.io.{ByteArrayInputStream, InputStreamReader}
import java.nio.charset.StandardCharsets
import java.util
import scala.collection.JavaConverters._

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

  /**
   * Represents a DMN file, could have been on disk or from a database etc.
   * @param locationURI locationURI used for imports, probably just the file name
   * @param bytes the raw xml file, boms and all
   */
  case class DMNFile(locationURI: String, bytes: Array[Byte]) extends Serializable

  /**
   * Model service definitions
   * @param name
   * @param namespace
   * @param service
   */
  case class DMNModelService(name: String, namespace: String, service: String) extends Serializable

  //, contextTypeName: String

  trait DMNContextProvider

  case class JSONContext(contextPath: String, child: Expression) extends UnaryExpression with CodegenFallback with DMNContextProvider {

    @transient
    lazy val mapper = new ObjectMapper()
      .registerModule(new SimpleModule()
        .addSerializer(classOf[ComparablePeriod], new DMNFEELComparablePeriodSerializer()))
      .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)

    override protected def withNewChildInternal(newChild: Expression): Expression = copy(child = newChild)

    override def nullSafeEval(input: Any): Any = {
      val i = input.asInstanceOf[UTF8String]
      val bb = i.getByteBuffer // handles the size of issues
      assert(bb.hasArray)

      val bain = new ByteArrayInputStream(
        bb.array(), bb.arrayOffset() + bb.position(), bb.remaining())

      val str = new InputStreamReader(bain, StandardCharsets.UTF_8)

      // assuming it's quicker than using classes
      val testData = // bytes is a couple of percents slower mapper.readValue(bb.array(), bb.arrayOffset() + bb.position(), bb.remaining(), classOf[java.util.Map[String, Object]])
        mapper.readValue(str, classOf[java.util.Map[String, Object]])

      (contextPath, testData)
    }

    override def dataType: DataType = ObjectType(classOf[(String, java.util.Map[String, Object])])
  }

  trait DMNResultProvider {
    def process(dmnResult: DMNResult): Any

    def dataType: DataType

    def nullable: Boolean
  }

  case class SeqOfBools() extends DMNResultProvider {

    override def process(dmnResult: DMNResult): Any = {
      if (dmnResult.getDecisionResults.getFirst.hasErrors || dmnResult.getDecisionResults.getFirst.getResult == null)
        null
      else
        new GenericArrayData(dmnResult.getDecisionResults.getFirst.getResult.asInstanceOf[util.ArrayList[Boolean]].toArray)
    }


    override def dataType: DataType = ArrayType(BooleanType)

    override def nullable: Boolean = true
  }

  trait DMNExpression extends Expression with CodegenFallback {

    def dmnFiles: Seq[DMNFile]
    def model: DMNModelService

    def children: Seq[Expression]
    def resultProvider: DMNResultProvider

    @transient
    lazy val dmnRuntime = {

      val resources = dmnFiles.map{ f =>
        val r = ResourceFactory.newByteArrayResource(f.bytes)
        f.locationURI -> r.setSourcePath(f.locationURI)
      }.toMap

      DMNRuntimeBuilder.fromDefaults()
        .setRelativeImportResolver((_,_, locationURI) => resources(locationURI).getReader)
        .buildConfiguration()
        .fromResources(resources.values.asJavaCollection)
        .getOrElseThrow(p => new RuntimeException(p))
    }

    //@transient
    //lazy val ctx = dmnRuntime.newContext() // the example pages show context outside of loops, we can re share it for a partition

    @transient
    lazy val dmnModel = dmnRuntime.getModel(model.name, model.namespace) // the example pages show context outside of loops, we can re share it for a partition

    override def dataType: DataType = resultProvider.dataType

    def evaluate(ctx: DMNContext): DMNResult

    override def eval(input: InternalRow): Any = {
      val ctx = dmnRuntime.newContext()
      children.foreach { child =>
        val res = child.eval(input)
        if (res != null) {
          val (contextPath: String, testData: Any) = res
          ctx.set(contextPath, testData)
        }
      }

      val dmnRes = evaluate(ctx)
      val res = resultProvider.process(dmnRes)
      res
    }

    override def nullable: Boolean = resultProvider.nullable

  }


  case class DMNDecisionService(dmnFiles: Seq[DMNFile], model: DMNModelService, children: Seq[Expression], resultProvider: DMNResultProvider) extends DMNExpression {
    assert(children.forall(_.isInstanceOf[DMNContextProvider]), "Input children must be DMNContextProvider's")

    override protected def withNewChildrenInternal(newChildren: IndexedSeq[Expression]): Expression = copy(children = newChildren)

    override def evaluate(ctx: DMNContext): DMNResult = dmnRuntime.evaluateDecisionService(dmnModel, ctx, model.service)
  }

  case class DMNEvaluateAll(dmnFiles: Seq[DMNFile], model: DMNModelService, children: Seq[Expression], resultProvider: DMNResultProvider) extends DMNExpression {
    assert(children.forall(_.isInstanceOf[DMNContextProvider]), "Input children must be DMNContextProvider's")

    override protected def withNewChildrenInternal(newChildren: IndexedSeq[Expression]): Expression = copy(children = newChildren)

    override def evaluate(ctx: DMNContext): DMNResult = dmnRuntime.evaluateAll(dmnModel, ctx)
  }

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
      val dmnModel = DMNModelService(ns, ns, "DQService")

      measure method "json dmn codegen - decision service" in {
        forceCodeGen {
          using(rows) afterTests {close()} in evaluate(_.withColumn("quality", column(DMNDecisionService(dmnFiles, dmnModel, Seq(JSONContext("testData", expression(col("payload")))), SeqOfBools()))), "json_dmn_codegen_decision_service")
        }
      }

      measure method "json dmn codegen - evaluate all" in {
        forceCodeGen {
          using(rows) afterTests {close()} in evaluate(_.withColumn("quality", column(DMNEvaluateAll(dmnFiles, dmnModel, Seq(JSONContext("testData", expression(col("payload")))), SeqOfBools()))), "json_dmn_codegen_evaluate_all")
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
      }

      measure method "json no forceEval in codegen compile evals false - extra config" in {
        forceCodeGen {
          extraPerfOptions {
            using(rows) afterTests {close()} in evaluate(_.withColumn("quality", ruleRunner(TestData.jsonRuleSuite, forceRunnerEval = false, compileEvals = false)), "json_no_forceEval_in_codegen_compile_evals_false_extra_config")
          }
        }
      }*/

      measure method "json no forceEval in codegen compile evals false - extra config fold" in {
        forceCodeGen {
          rewriteAndFold {
            using(rows) afterTests {close()} in evaluate(_.withColumn("quality", ruleRunner(TestData.jsonRuleSuite, forceRunnerEval = false, compileEvals = false)), "json_no_forceEval_in_codegen_compile_evals_false_extra_config_fold")
          }
        }
      }

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
