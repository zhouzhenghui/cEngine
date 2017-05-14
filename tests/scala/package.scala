package tests.scala

import org.scalatest._
import better.files._

import scala.c_engine._
import scala.concurrent._
import scala.concurrent.duration._
import scala.util.{Success, Failure}
import ExecutionContext.Implicits.global

object TestClasses {
  
  var totalTime: Double = 0.0
  
  class StandardTest extends FlatSpec {
    
    def checkResults(code: String, shouldBootstrap: Boolean = true): Unit = checkResults2(Seq(code), shouldBootstrap)
    
    def checkResults2(codeInFiles: Seq[String], shouldBootstrap: Boolean = true) = {

      val gccOutputFuture = Future[Seq[String]] { Gcc.compileAndGetOutput(codeInFiles) }
    
      val cEngineOutputFuture = Future[List[String]] {
        val start = System.nanoTime
        val state = new State
        if (shouldBootstrap) {
          Executor.init(codeInFiles, true, state)
        } else {
          Executor.init(Seq("#define HAS_FLOAT\n" + File("src\\scala\\c_engine\\ee_printf.c").contentAsString) ++ codeInFiles.map{code => "#define printf ee_printf \n" + code}, true, state)
        }

        Executor.run(state)
        totalTime += (System.nanoTime - start)/1000000000.0
        state.stdout.toList
      }

      val testExe = for {
        gcc <- gccOutputFuture
        cEngine <- cEngineOutputFuture
      } yield (gcc, cEngine)
      
      val result = Await.ready(testExe, Duration.Inf).value.get
      
      result match {
        case Success((gccOutput, cEngineOutput)) => 
          info("C_Engine output: " + cEngineOutput)
          info("Gcc output: " + gccOutput)
          assert(cEngineOutput == gccOutput)
        case Failure(e) => 
          e.printStackTrace()
          false
      }
    }
  }
}