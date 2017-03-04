package app.astViewer

import org.eclipse.cdt.core.dom.ast._
import java.util.Formatter
import java.util.Locale;
import scala.collection.mutable.HashMap
import org.eclipse.cdt.internal.core.dom.parser.c.CBasicType
import scala.collection.mutable.ListBuffer
import org.eclipse.cdt.internal.core.dom.parser.c.CPointerType

// 'isNative' implies the function is in C, not Scala
abstract case class Function(name: String, isNative: Boolean) {
  
  var index = -1
  
  def parameters: List[IType]
  def run(argumentAddress: Address, call: IASTFunctionCallExpression, state: State): IASTNode = {
    var startingAddress = argumentAddress
    val args = new ListBuffer[AnyVal]()
    if (name != "printf") {
      val argumentsFromMemory = parameters.foreach { argType =>
        val newArg = state.readVal(startingAddress, argType).value
        args += TypeHelper.resolve(newArg)(state).value
        startingAddress += TypeHelper.sizeof(argType)
      }
    } else {
      val numArgs = call.getArguments.size
      // for printf, a scala-based var-arg function, pop off the stack
      for (i <- (0 until numArgs)) {
        val arg = state.stack.pop
        args += (arg match {
          case addr @ Address(_) => addr
          case x => TypeHelper.resolve(x)(state).value
        })
      }
    }
    
    run(args.toArray, state)
  }
  
  protected def run(formattedOutputParams: Array[AnyVal], state: State): IASTNode
}

object Functions {
  
  var varArgStartingAddr = 0
  
  val scalaFunctions = List[Function](
      new Function("rand", false) {
        def parameters = List()
        def run(formattedOutputParams: Array[AnyVal], state: State): IASTNode = {
          state.stack.push(Math.abs(scala.util.Random.nextInt))
          null
        }
      },
      new Function("isalpha", false) {
        def parameters = List(new CBasicType(IBasicType.Kind.eChar, 1))
        def run(formattedOutputParams: Array[AnyVal], state: State): IASTNode = {
          val theChar = formattedOutputParams.head.asInstanceOf[Character].toChar
          state.stack.push(if (theChar.isLetter) 1 else 0) 
          null
        }
      },
      new Function("tolower", false) {
        def parameters = List(new CBasicType(IBasicType.Kind.eChar, 1))
        def run(formattedOutputParams: Array[AnyVal], state: State): IASTNode = {
          val theChar = formattedOutputParams.head.asInstanceOf[Character].toChar
          state.stack.push(theChar.toLower.toByte)
          null
        }
      },
      new Function("toupper", false) {
        def parameters = List(new CBasicType(IBasicType.Kind.eChar, 1))
        def run(formattedOutputParams: Array[AnyVal], state: State): IASTNode = {
          val theChar = formattedOutputParams.head.asInstanceOf[Character].toChar
          state.stack.push(theChar.toUpper.toByte)
          null
        }
      },
      new Function("isupper", false) {
        def parameters = List(new CBasicType(IBasicType.Kind.eChar, 1))
        def run(formattedOutputParams: Array[AnyVal], state: State): IASTNode = {
          val theChar = formattedOutputParams.head.asInstanceOf[Character].toChar
          state.stack.push(if (theChar.isUpper) 1 else 0)
          null
        }
      },
      new Function("calloc", false) {
        def parameters = List(new CBasicType(IBasicType.Kind.eInt, 4),
                              new CBasicType(IBasicType.Kind.eInt, 4))
        def run(formattedOutputParams: Array[AnyVal], state: State): IASTNode = {
          val numBlocks = formattedOutputParams(0).asInstanceOf[Int]
          val blockSize = formattedOutputParams(1).asInstanceOf[Int]
          val addr = state.allocateSpace(numBlocks * blockSize)
          state.stack.push(addr)
          null
        }
      },
      new Function("malloc", false) {
        def parameters = List(new CBasicType(IBasicType.Kind.eInt, 4))
        def run(formattedOutputParams: Array[AnyVal], state: State): IASTNode = {
          val returnVal = formattedOutputParams.head match {
            case long: Long => state.allocateSpace(long.toInt)
            case int: Int => state.allocateSpace(int)
          }
          state.stack.push(returnVal)
          null
        }
      },
      new Function("realloc", false) {
        def parameters = List(new CBasicType(IBasicType.Kind.eInt, 4))
        def run(formattedOutputParams: Array[AnyVal], state: State): IASTNode = {
          state.stack.push(state.allocateSpace(formattedOutputParams.head.asInstanceOf[Int]))
          null
        }
      },
      new Function("memmove", false) {
        def parameters = List(new CBasicType(IBasicType.Kind.eInt, 4), new CBasicType(IBasicType.Kind.eInt, 4), new CBasicType(IBasicType.Kind.eInt, 4))
        def run(formattedOutputParams: Array[AnyVal], state: State): IASTNode = {
          val dst = formattedOutputParams(0).asInstanceOf[Address]
          val src = formattedOutputParams(1).asInstanceOf[Address]
          val numBytes = formattedOutputParams(2).asInstanceOf[Int]
          
          state.copy(dst, src, numBytes)
          null
        }
      },
      new Function("memcpy", false) {
        def parameters = List(new CBasicType(IBasicType.Kind.eInt, 4), new CBasicType(IBasicType.Kind.eInt, 4), new CBasicType(IBasicType.Kind.eInt, 4))
        def run(formattedOutputParams: Array[AnyVal], state: State): IASTNode = {
          val dst = formattedOutputParams(0).asInstanceOf[Int]
          val src = formattedOutputParams(1).asInstanceOf[Int]
          val numBytes = formattedOutputParams(2).asInstanceOf[Int]

          state.copy(Address(dst), Address(src), numBytes)
          
          null
        }
      },
      new Function("_assert", false) {
        def parameters = List(new CBasicType(IBasicType.Kind.eInt, 4))
        def run(formattedOutputParams: Array[AnyVal], state: State): IASTNode = {
          val addy = formattedOutputParams(0).asInstanceOf[Address]
          println(Utils.readString(addy)(state) + " FAILED")
          null
        }
      },
      new Function("modf", false) {
        def parameters = List(new CBasicType(IBasicType.Kind.eDouble, 4), new CPointerType(new CBasicType(IBasicType.Kind.eDouble, 0), 0))
        def run(formattedOutputParams: Array[AnyVal], state: State): IASTNode = {
          val fraction = formattedOutputParams(0).asInstanceOf[Double]
          val intPart = formattedOutputParams(1).asInstanceOf[Int]
          
          state.setValue(fraction.toInt, Address(intPart))
          
          state.stack.push(fraction % 1.0)
          null
        }
      },
      new Function("putchar", false) {
        def parameters = List(new CBasicType(IBasicType.Kind.eChar, 1))
        def run(formattedOutputParams: Array[AnyVal], state: State): IASTNode = {
          val theChar = formattedOutputParams(0).asInstanceOf[Character]
          if (theChar == 10) {
            state.stdout += Functions.standardOutBuffer
            Functions.standardOutBuffer = ""
          } else {
            Functions.standardOutBuffer += theChar.toChar
          }
          null
        }
      },
      new Function("printf", false) {
        def parameters = List(new CBasicType(IBasicType.Kind.eInt, 4))
        def run(formattedOutputParams: Array[AnyVal], state: State): IASTNode = {
          val resolved = formattedOutputParams.map{x => 
            x match {
              case strLit: StringLiteral => strLit.str
              case addy @ Address(addr) => {
                  // its a string!
                Utils.readString(addy)(state)
              }
              case int: Int => int
              case long: Long => long
              case short: Short => short
              case byte: Byte => byte
              case double: Double => double
              case float: Float => float
              case char: Character => char
            }
          }
          
          Functions.printf(state, resolved.map(_.asInstanceOf[Object]))
          null
        }
      },
      new Function("strlen", false) {
        def parameters = List(new CBasicType(IBasicType.Kind.eInt, 4))
        def run(formattedOutputParams: Array[AnyVal], state: State): IASTNode = {
          val straddy = formattedOutputParams.head match {
            //case AddressInfo(addr, _) => addr.value
            case Address(addr) => addr
            case int: Int => int
          }
          var current: Character = 0
          var i = 0
          do {
            current = state.readVal(Address(straddy + i), new CBasicType(IBasicType.Kind.eChar, 0)).value.asInstanceOf[Character]
            if (current != 0) {
              i += 1
            }
          } while (current != 0)
          state.stack.push(i)
          null
        }
      },
      new Function("free", false) {
        def parameters = List(new CBasicType(IBasicType.Kind.eInt, 4))
        def run(formattedOutputParams: Array[AnyVal], state: State): IASTNode = {
          null
        }
      },
      new Function("va_arg", false) {
        def parameters = List(new CBasicType(IBasicType.Kind.eInt, 0),
            new CBasicType(IBasicType.Kind.eInt, 4))
        def run(formattedOutputParams: Array[AnyVal], state: State): IASTNode = {
          val argType = formattedOutputParams(0).asInstanceOf[Int]
          val list = formattedOutputParams(1).asInstanceOf[Int]
          val result = state.readVal(Address(varArgStartingAddr), TypeHelper.pointerType).value
          state.stack.push(result)
          varArgStartingAddr += 4
          null
        }
      },
      new Function("va_start", false) {
        def parameters = List(new CBasicType(IBasicType.Kind.eInt, 0),
            new CBasicType(IBasicType.Kind.eInt, 0))
        def run(formattedOutputParams: Array[AnyVal], state: State): IASTNode = {
          val lastNamedArgAddr = formattedOutputParams(1).asInstanceOf[Int]
          val listAddr = formattedOutputParams(0).asInstanceOf[Int]
          varArgStartingAddr = lastNamedArgAddr + 4
          null
        }
      },
      new Function("va_end", false) {
        def parameters = List(new CBasicType(IBasicType.Kind.eInt, 4))
        def run(formattedOutputParams: Array[AnyVal], state: State): IASTNode = {
          null
        }
      }  
  )
  
  var standardOutBuffer = ""

  def printf(context: State, theArgs: Seq[Object]) = {
    val args = theArgs.reverse
    val formatString = args.head.asInstanceOf[String].replaceAll("^\"|\"$", "").replaceAll("%ld", "%d").replaceAll("%l", "%d").replaceAll(10.asInstanceOf[Char].toString, System.lineSeparator())

    val buffer = new StringBuffer();
    val formatter = new Formatter(buffer, Locale.US);
    
    val resolvedStrings = args.tail.map{ _ match {
      case str: String => 
        val resolved = str.replaceAll(10.asInstanceOf[Char].toString, System.lineSeparator())
        resolved.split(System.lineSeparator()).mkString
      case x => x 
    }}.toArray
    
    formatter.format(formatString, resolvedStrings: _*)

    context.stdout ++= buffer.toString.split(System.lineSeparator())
  }
}