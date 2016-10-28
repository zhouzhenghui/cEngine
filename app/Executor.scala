package app.astViewer

import org.eclipse.cdt.core.dom.ast._

import scala.collection.mutable.{ ListBuffer, Stack }
import scala.util.control.Exception.allCatch
import java.util.Formatter;
import java.util.Locale;
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.eclipse.cdt.internal.core.dom.parser.c._
import java.math.BigInteger
import org.eclipse.cdt.core.dom.ast.IBasicType.Kind._
import scala.collection.mutable.Map

case class VarRef(name: String)
case class StringLiteral(str: String) extends AnyVal

case class Literal(litStr: String) {
  def cast: AnyVal = {

    def isIntNumber(s: String): Boolean = (allCatch opt s.toInt).isDefined
    def isLongNumber(s: String): Boolean = (allCatch opt s.toLong).isDefined
    def isDoubleNumber(s: String): Boolean = (allCatch opt s.toDouble).isDefined
    
    val isLong = litStr.endsWith("L")
    val isUnsignedLong = litStr.endsWith("UL")
    
    val pre: String = if (litStr.endsWith("L")) {
      litStr.take(litStr.size - 1).mkString
    } else if (litStr.endsWith("UL")) {
      litStr.take(litStr.size - 2).mkString
    } else {
      litStr
    }
    
    val lit = if (pre.startsWith("0x")) {
      val bigInt = new BigInteger(pre.drop(2), 16);
      bigInt.toString
    } else {
      pre
    }

    if (lit.head == '\"' && lit.last == '\"') {
      StringLiteral(lit)
    } else if (lit.head == '\'' && lit.last == '\'' && lit.length == 3) {
      lit.toCharArray.apply(1)
    } else if (isIntNumber(lit)) {
      lit.toInt
    } else if (isLongNumber(lit)) {
      lit.toLong
    } else if (lit.contains('F') || lit.contains('f')) {
      val num = lit.toCharArray.filter(x => x != 'f' && x != 'F').mkString
      num.toFloat
    } else if (lit == "'\\0'") {
      0.toChar
    } else {
      lit.toDouble
    }
  }
  
  def typeCast(theType: IBasicType): AnyVal = {
    TypeHelper.coerece(theType, cast)
  }
}

class State {
  
  val executionContext = new Stack[FunctionExecutionContext]()
  val globals = Map[String, RuntimeVariable]()
  var vars: FunctionExecutionContext = null
  val functionMap = scala.collection.mutable.Map[String, IASTNode]()
  val stdout = new ListBuffer[String]()

  // flags
  var isReturning = false
  var isBreaking = false
  var isContinuing = false
  var isPreprocessing = true
  
  def stack = {
    vars.stack
  }

  def callFunction(call: IASTFunctionCallExpression, args: Seq[Any]) = {
    executionContext.push(vars)

    vars = new FunctionExecutionContext(globals, call.getExpressionType)
    vars.pathStack.push(call)
    
        // load up the stack with the parameters
    args.reverse.foreach { arg => vars.stack.push(arg)}

    val name = call.getFunctionNameExpression match {
      case x: IASTIdExpression => x.getName.getRawSignature
      case _                   => "Error"
    }

    Seq(functionMap(name))
  }

  def clearVisited(parent: IASTNode) {
    vars.visited -= parent
    parent.getChildren.foreach { node =>
      clearVisited(node)
    }
  }

  private val data = ByteBuffer.allocate(100000);
  data.order(ByteOrder.LITTLE_ENDIAN)

  var insertIndex = 0

  def allocateSpace(numBytes: Int): Address = {
    if (numBytes > 0) {
      val result = insertIndex
      insertIndex += numBytes
      Address(result)
    } else {
      Address(0)
    }
  }

  def readVal(address: Int, theType: IBasicType): AnyVal = {

    import org.eclipse.cdt.core.dom.ast.IBasicType.Kind._

    // if it is neither signed or unsigned, assume its signed
    val isSigned = TypeHelper.isSigned(theType)

    if (theType.isShort && isSigned) {
      data.getShort(address)
    } else if (theType.isShort && !isSigned) {
      data.getShort(address) & 0xFFFF
    } else if (theType.getKind == eInt && theType.isLong) {
      data.getLong(address)
    } else if (theType.getKind == eInt) {
      data.getInt(address)
    } else if (theType.getKind == eDouble) {
      data.getDouble(address)
    } else if (theType.getKind == eFloat) {
      data.getFloat(address)
    } else if (theType.getKind == eChar && isSigned) {
      data.get(address).toChar
    } else if (theType.getKind == eChar && !isSigned) {
      data.get(address).toChar & 0xFF
    }
  }

  // use Address type to prevent messing up argument order
  def setValue(newVal: AnyVal, info: AddressInfo): Unit = {

    newVal match {
      case Address(addy)        => setValue(addy, info)
      case x => {
        
        if (info.theType.isInstanceOf[IPointerType] || info.theType.isInstanceOf[CStructure]) {
          newVal match {
            case newVal: Char    => data.put(info.address.value, newVal.toByte) // MUST convert to byte because writing char is 2 bytes!!!
            case newVal: Long    => data.putLong(info.address.value, newVal)
            case newVal: Int     => data.putInt(info.address.value, newVal)
            case newVal: Float   => data.putFloat(info.address.value, newVal)
            case newVal: Double  => data.putDouble(info.address.value, newVal)
            case newVal: Boolean => data.putChar(info.address.value, if (newVal) 1 else 0)
          }
        } else {
          val theType = TypeHelper.resolve(info.theType)
      
          import IBasicType.Kind._        
          
          theType.getKind match {
            case `eChar`    => data.put(info.address.value, TypeHelper.coerece(theType, newVal).asInstanceOf[Byte])
            case `eInt` if theType.isLong => data.putLong(info.address.value, TypeHelper.coerece(theType, newVal).asInstanceOf[Long])
            case `eInt` if theType.isShort    => data.putShort(info.address.value, TypeHelper.coerece(theType, newVal).asInstanceOf[Short]) 
            case `eInt`     => data.putInt(info.address.value, TypeHelper.coerece(theType, newVal).asInstanceOf[Int]) 
            case `eFloat`   => data.putFloat(info.address.value, TypeHelper.coerece(theType, newVal).asInstanceOf[Float])
            case `eDouble`  => data.putDouble(info.address.value, TypeHelper.coerece(theType, newVal).asInstanceOf[Double])
            case `eBoolean` => data.putChar(info.address.value, if (TypeHelper.coerece(theType, newVal).asInstanceOf[Boolean]) 1 else 0)
          }
      }}
    }
  }
}

case class Address(value: Int) extends AnyVal {
  def +(x: Int) = {
    Address(value + x)
  }
}
case class AddressInfo(address: Address, theType: IType)

object TypeHelper {
  
  def coerece(theType: IBasicType, newVal: Any): AnyVal = {
    theType.getKind match {
      case `eChar`    => 
        newVal match {
          case int: Int => int.toByte// MUST convert to byte because writing char is 2 bytes!!!
          case char: Char => char.toByte
          case byte: Byte => byte
        } 
     case `eInt` if theType.isLong =>
        newVal match {
          case int: Int => int.toLong
          case long: Long => long
        } 
     case `eInt` if theType.isShort && TypeHelper.isSigned(theType) =>
        newVal match {
          case int: Int => int.toShort
          case short: Short => short
        }
     case `eInt` if theType.isShort && !TypeHelper.isSigned(theType) =>
        newVal match {
          case int: Int => int.toShort
          case short: Short => short
          case long: Long => long.toShort
        }  
     case `eInt`     => 
        newVal match {
          case boolean: Boolean => if (boolean) 1 else 0
          case long: Long => long.toInt
          case int: Int => int
          case short: Short => short.toInt
          case char: Char => char.toByte.toInt
          case double: Double => double.toInt
        }  
     case `eFloat`   =>
        newVal match {
          case int: Int => int.toFloat
          case double: Double => double.toFloat
          case float: Float => float
        }  
     case `eDouble`  =>
        newVal match {
          case int: Int => int.toDouble
          case double: Double => double
          case float: Float => float.toDouble
        } 
      case `eBoolean` =>
        newVal match {
          case bool: Boolean => 1
          case int: Int => if (int > 0) 1 else 0
        } 
      case `eVoid` =>
        newVal match {
          case int: Int => int
          case double: Double => double
          case float: Float => float
          case char: Char => char
        } 
    }
  }
  
  def isSigned(theType: IBasicType) = {
    theType.isSigned || (!theType.isSigned && !theType.isUnsigned)
  }
  
  // resolves 'Any' to 'AnyVal'
  def resolve(state: State, theType: IType, any: Any): AnyVal = {
    any match {
      case VarRef(name) =>
        state.vars.resolveId(name).value
      case lit @ Literal(_) => lit.typeCast(TypeHelper.resolve(theType))
      case AddressInfo(addy, _) => addy.value
      case Address(addy) => addy
      case int: Int => int
      case float: Float => float
      case double: Double => double
      case long: Long => long
      case boolean: Boolean => boolean
    }
  }

  def resolve(theType: IType): IBasicType = theType match {
    case struct: CStructure       => new CBasicType(IBasicType.Kind.eInt, 0)
    case basicType: IBasicType    => basicType
    case typedef: ITypedef        => resolve(typedef.getType)
    case ptrType: IPointerType    => resolve(ptrType.getType)
    case arrayType: IArrayType    => resolve(arrayType.getType)
    case qualType: IQualifierType => resolve(qualType.getType)
  }

  def sizeof(theType: IType): Int = theType match {
    case ptr: IPointerType =>
      4
    case struct: CStructure =>
      struct.getFields.map { field =>
        sizeof(field.getType)
      }.sum
    case array: IArrayType =>
      sizeof(array.getType)
    case typedef: CTypedef =>
      sizeof(typedef.getType)
    case qual: IQualifierType =>
      sizeof(qual.getType)
    case basic: IBasicType =>
      basic.getKind match {
        case `eInt` if basic.isLong => 8
        case `eInt`                 => 4
        case `eFloat`               => 4
        case `eChar16`              => 2
        case `eDouble`              => 8
        case `eChar`                => 1
        case `eChar32`              => 4
        case `eVoid`                => 4
      }
  }
  
  def isPointer(theType: IType) = theType.isInstanceOf[IArrayType] || theType.isInstanceOf[IPointerType]
}

trait RuntimeVariable {
  val state: State
  val theType: IType
  def address: Address
  
  val size = TypeHelper.sizeof(theType)

  def sizeof: Int
  def info = AddressInfo(address, theType)
  
  def value: AnyVal = {
    if (TypeHelper.isPointer(theType)) {
      state.readVal(address.value, new CBasicType(IBasicType.Kind.eInt, 0))
    } else {
      state.readVal(address.value, TypeHelper.resolve(theType))
    }
  }

  def allocateSpace(state: State, aType: IType, numElements: Int): Address = {
    if (TypeHelper.isPointer(aType)) {
      val intType = new CBasicType(IBasicType.Kind.eInt, 0)
      state.allocateSpace(TypeHelper.sizeof(intType))
    } else if (aType.isInstanceOf[CStructure]) {
      val struct = aType.asInstanceOf[CStructure]
      var result: Address = Address(-1)
      struct.getFields.foreach { field =>
        if (result == Address(-1)) {
          result = allocateSpace(state, field.getType, numElements)
        } else {
          allocateSpace(state, field.getType, numElements)
        }
      }
      result
    } else if (aType.isInstanceOf[CTypedef]) {
      allocateSpace(state, aType.asInstanceOf[CTypedef].getType, numElements)
    } else {
      state.allocateSpace(TypeHelper.sizeof(aType) * numElements)
    }
  }
}

protected class ArrayVariable(val state: State, val theType: IType, dimensions: Seq[Int]) extends RuntimeVariable {

  val numElements = if (dimensions.isEmpty) 0 else dimensions.reduce{_ * _}

  // where we store the actual data
  val theArrayAddress = allocateSpace(state, TypeHelper.resolve(theType), numElements)

  // where we store the reference
  val address: Address = allocateSpace(state, theType, 1)

  state.setValue(theArrayAddress.value, AddressInfo(info.address, new CBasicType(IBasicType.Kind.eInt, 0)))
  
  def resolved = TypeHelper.resolve(theType)

  def sizeof: Int = {
    TypeHelper.sizeof(theType) * numElements
  }

  def setValue(value: Any): Unit = value match {
    case array: Array[_] =>
      var i = 0
      array.foreach { element =>
        element match {
          case lit @ Literal(_) =>
            state.setValue(lit.typeCast(resolved), AddressInfo(theArrayAddress + i, resolved))
          case int: Int =>
            state.setValue(int, AddressInfo(theArrayAddress + i, resolved))
          case char: Char =>
            state.setValue(char, AddressInfo(theArrayAddress + i, resolved))
          case double: Double =>
            state.setValue(double, AddressInfo(theArrayAddress + i, resolved))
        }
        i += TypeHelper.sizeof(resolved)
      }
  }
}

protected class Variable(val state: State, val theType: IType) extends RuntimeVariable {

  val address: Address = allocateSpace(state, theType, 1)

  def sizeof: Int = {
    TypeHelper.sizeof(theType)
  }
}

class FunctionExecutionContext(globals: Map[String, RuntimeVariable], val returnType: IType) {
  val visited = new ListBuffer[IASTNode]()
  val varMap = Map[String, RuntimeVariable]() ++ globals
  val pathStack = new Stack[IASTNode]()
  val stack = new Stack[Any]()

  def resolveId(id: String) = varMap(id)
  def addVariable(id: String, theVar: RuntimeVariable) = varMap += (id -> theVar) 
}

object Executor {

  // 'node' must be a IASTCaseStatement or a IASTDefaultStatement
  def processSwitch(node: IASTNode): Seq[IASTNode] = {
    val codeToRun = new ListBuffer[IASTNode]()

    val siblings = node.getParent.getChildren

    var isSelfFound = false
    siblings.foreach { sib =>
      if (sib == node) {
        isSelfFound = true
      } else if (isSelfFound && !sib.isInstanceOf[IASTCaseStatement]) {
        codeToRun += sib
      }
    }

    codeToRun
  }

  def parseStatement(statement: IASTStatement, state: State, direction: Direction): Seq[IASTNode] = statement match {
    case breakStatement: IASTNullStatement =>
      Seq()
    case breakStatement: IASTBreakStatement =>
      state.isBreaking = true
      Seq()
    case continueStatement: IASTContinueStatement =>
      state.isContinuing = true
      Seq()
    case switch: IASTSwitchStatement =>
      val cases = switch.getBody.getChildren.collect { case x: IASTCaseStatement => x; case y: IASTDefaultStatement => y }
      if (direction == Entering) {
        Seq(switch.getControllerExpression) ++ cases // only process case and default statements
      } else {
        Seq()
      }
    case default: IASTDefaultStatement =>
      if (direction == Exiting) {
        processSwitch(default)
      } else {
        Seq()
      }
    case caseStatement: IASTCaseStatement =>
      if (direction == Entering) {
        Seq(caseStatement.getExpression)
      } else {

        val caseExpr = state.stack.pop.asInstanceOf[Literal].cast
        val switchExpr = state.stack.pop

        state.stack.push(switchExpr)

        val resolved = switchExpr match {
          case VarRef(x) => state.vars.resolveId(x).value
          case int: Int  => int
        }

        if (caseExpr.asInstanceOf[Int] == resolved.asInstanceOf[Int]) {
          processSwitch(caseStatement)
        } else {
          Seq()
        }
      }
    case doWhileLoop: IASTDoStatement =>
      if (direction == Entering) {
        Seq(doWhileLoop.getBody, doWhileLoop.getCondition)
      } else {
        val shouldLoop = state.stack.pop match {
          case x: Int     => x == 1
          case x: Boolean => x
        }

        if (shouldLoop) {
          state.clearVisited(doWhileLoop.getBody)
          state.clearVisited(doWhileLoop.getCondition)

          Seq(doWhileLoop.getBody, doWhileLoop.getCondition, doWhileLoop)
        } else {
          Seq()
        }
      }
    case whileLoop: IASTWhileStatement =>
      if (direction == Entering) {
        Seq(whileLoop.getCondition)
      } else {

        val cast = state.stack.pop match {
          case lit @ Literal(_) => lit.cast
          case x                => x
        }

        val shouldLoop = cast match {
          case x: Int     => x == 1
          case x: Boolean => x
        }

        if (shouldLoop) {
          state.clearVisited(whileLoop.getBody)
          state.clearVisited(whileLoop.getCondition)

          Seq(whileLoop.getBody, whileLoop.getCondition, whileLoop)
        } else {
          Seq()
        }
      }
    case ifStatement: IASTIfStatement =>
      if (direction == Entering) {
        Seq(ifStatement.getConditionExpression)
      } else {
        val result = state.stack.pop

        val value = result match {
          case VarRef(name) =>
            state.vars.resolveId(name).value
          case lit @ Literal(_) =>
            lit.cast
          case x => x
        }

        val conditionResult = value match {
          case x: Int     => x == 1
          case x: Boolean => x
        }
        if (conditionResult) {
          Seq(ifStatement.getThenClause)
        } else if (ifStatement.getElseClause != null) {
          Seq(ifStatement.getElseClause)
        } else {
          Seq()
        }
      }
    case forLoop: IASTForStatement =>
      if (direction == Entering) {
        Seq(Option(forLoop.getInitializerStatement), Option(forLoop.getConditionExpression)).flatten
      } else {
        val shouldKeepLooping = if (forLoop.getConditionExpression != null) {
          state.stack.pop.asInstanceOf[Boolean]
        } else {
          true
        }

        if (shouldKeepLooping) {
          state.clearVisited(forLoop.getBody)
          state.clearVisited(forLoop.getIterationExpression)
          
          if (forLoop.getConditionExpression != null) {
            state.clearVisited(forLoop.getConditionExpression)
          }

          Seq(Option(forLoop.getBody), Option(forLoop.getIterationExpression), Option(forLoop.getConditionExpression), Some(forLoop)).flatten
        } else {
          Seq()
        }
      }
    case ret: IASTReturnStatement =>
      if (direction == Entering) {
        if (ret.getReturnValue != null) {
          Seq(ret.getReturnValue)
        } else {
          Seq()
        }
      } else {
        // resolve everything before returning
        if (ret.getReturnValue != null) {
          val returnVal = state.stack.pop
          state.stack.push(returnVal match {
            case lit @ Literal(_) if state.vars.returnType != null => lit.typeCast(TypeHelper.resolve(state.vars.returnType))
            case lit @ Literal(_) => lit.cast
            case VarRef(id)       => 
              if (state.vars.returnType != null) {
                if (TypeHelper.isPointer(state.vars.returnType)) {
                  state.vars.resolveId(id).value.asInstanceOf[Int]
                } else {
                  TypeHelper.coerece(TypeHelper.resolve(state.vars.returnType), state.vars.resolveId(id).value)
                }
              } else {
                state.vars.resolveId(id).value
              }
            case int: Int         => int
            case doub: Double     => doub
            case bool: Boolean    => bool
          })
        }
        state.isReturning = true
        
        Seq()
      }
    case decl: IASTDeclarationStatement =>
      if (direction == Entering) {
        Seq(decl.getDeclaration)
      } else {
        Seq()
      }
    case compound: IASTCompoundStatement =>
      if (direction == Entering) {
        compound.getStatements
      } else {
        Seq()
      }
    case exprStatement: IASTExpressionStatement =>
      if (direction == Entering) {
        Seq(exprStatement.getExpression)
      } else {
        Seq()
      }
  }

  def createStringVariable(state: State, name: String, theType: IType, str: String): ArrayVariable = {
    val theStr = Utils.stripQuotes(str)
    val withNull = if (!theStr.isEmpty) {
      theStr.toCharArray() :+ 0.toChar // terminating null char
    } else {
      Array()
    }
    val theArrayPtr = new ArrayVariable(state, theType, Seq(withNull.size))
    theArrayPtr.setValue(withNull)
    state.vars.addVariable(name, theArrayPtr)
    theArrayPtr
  }
  
  def parseDeclarator(decl: IASTDeclarator, direction: Direction, state: State): Seq[IASTNode] = {
    if (direction == Entering) {
      decl match {
        case array: IASTArrayDeclarator =>
          Seq(Option(decl.getInitializer)).flatten ++ array.getArrayModifiers
        case _ =>
          Seq(Option(decl.getInitializer)).flatten
      }
    } else {
      val nameBinding = decl.getName.resolveBinding()
      
      if (nameBinding.isInstanceOf[IVariable]) {
        val theType = nameBinding.asInstanceOf[IVariable].getType match {
          case typedef: CTypedef => typedef.getType
          case x                 => x
        }

        state.stack.push(decl.getName.getRawSignature)

        val name = state.stack.pop.asInstanceOf[String]

        decl match {
          case arrayDecl: IASTArrayDeclarator =>

            val dimensions = arrayDecl.getArrayModifiers.filter{_.getConstantExpression != null}.map{dim => state.stack.pop match {
              case lit: Literal => lit.cast.asInstanceOf[Int]
              case VarRef(id) => state.vars.resolveId(id).value.asInstanceOf[Int]
            }}
            
            val initializer = decl.getInitializer.asInstanceOf[IASTEqualsInitializer]
            
            // Oddly enough, it is possible to have a pointer to an array with no dimensions or initializer:
            //    extern char *x[]
            
            if (dimensions.isEmpty && initializer != null) {             
              if (TypeHelper.resolve(theType).getKind == eChar && !initializer.getInitializerClause.isInstanceOf[IASTInitializerList]) {
                // char str[] = "Hello!\n";
                val initString = state.stack.pop.asInstanceOf[Literal].cast.asInstanceOf[StringLiteral].str                
                createStringVariable(state, name, theType, initString)
              } else {
                val list = initializer.getInitializerClause.asInstanceOf[IASTInitializerList]
                val size = list.getSize
                
                val values = (0 until size).map{x => state.stack.pop match {
                  case lit: Literal => lit.cast
                  case int: Int => int
                }}.reverse
  
                val theArrayPtr = new ArrayVariable(state, theType.asInstanceOf[IArrayType], Array(size))
                theArrayPtr.setValue(values.toArray)
                state.vars.addVariable(name, theArrayPtr)
              }
            } else {
              val numElements = if (dimensions.isEmpty) 0 else dimensions.reduce{_ * _}
              val initialArray = Array.fill[Any](numElements)(0)

              if (!state.stack.isEmpty) {
                var i = 0
                for (i <- (numElements - 1) to 0 by -1) {
                  val newInit = state.stack.pop
                  initialArray(i) = newInit
                }
              }
              
              val theArrayPtr = new ArrayVariable(state, theType.asInstanceOf[IArrayType], dimensions)
              theArrayPtr.setValue(initialArray)
              state.vars.addVariable(name, theArrayPtr)
            }
          case decl: CASTDeclarator =>

            def createVariable(theType: IType, name: String): RuntimeVariable = theType match {
              case struct: CStructure =>
                val newStruct = new Variable(state, theType)
                state.vars.addVariable(name, newStruct)
                newStruct
              case typedef: CTypedef =>
                createVariable(typedef.getType, name)
              case qual: CQualifierType =>
                createVariable(qual.getType, name)
              case ptr: IPointerType =>
                val initVal = if (!state.stack.isEmpty) {
                  state.stack.pop
                } else {
                  0
                }

                initVal match {  
                  case Literal(str) if !ptr.getType.isInstanceOf[CStructure] && TypeHelper.resolve(ptr.getType).getKind == `eChar` => 
                    // char *str = "hello world!";
                    
                    val initString = initVal.asInstanceOf[Literal].cast match {
                      case StringLiteral(str) => str
                      case int: Int if int == 0 => "\"\"" // null initializer
                    }
                    
                    createStringVariable(state, name, theType, initString)
                  case _ =>
                    val newVar = new Variable(state, theType)
                      initVal match {
                        case VarRef(id) => 
                          val variable = state.vars.resolveId(id)
                          state.setValue(variable.value, newVar.info)
                        case AddressInfo(address, theType) => 
                          if (TypeHelper.isPointer(theType)) {
                            state.setValue(state.readVal(address.value, TypeHelper.resolve(theType)), newVar.info)
                          } else {
                            state.setValue(address.value, newVar.info)
                          }
                        case int: Int => state.setValue(int, newVar.info)
                        case _ =>
                    }
                    state.vars.addVariable(name, newVar)
                    newVar 
                }
                
               
              case basic: IBasicType =>
                
                val initVal = if (!state.stack.isEmpty) {
                  state.stack.pop
                } else {
                  0
                }   
                
                val resolved = TypeHelper.resolve(state, theType, initVal)
                val newVar = new Variable(state, theType)
                state.setValue(resolved, newVar.info)
                state.vars.addVariable(name, newVar)
                newVar
            }

            val newVar = createVariable(theType, name)
            
            if (decl.getInitializer != null && decl.getInitializer.isInstanceOf[IASTEqualsInitializer]
                 && decl.getInitializer.asInstanceOf[IASTEqualsInitializer].getInitializerClause.isInstanceOf[IASTInitializerList]) {

              val fields = theType.asInstanceOf[CStructure].getFields
              val size = fields.size
              
              val values = fields.map{x => state.stack.pop.asInstanceOf[Literal].cast}.reverse zip fields

              var offset = 0
              values.foreach { case (value, field) =>
                state.setValue(value, AddressInfo(newVar.address + offset, field.getType))
                offset += TypeHelper.sizeof(field.getType)
              }
            }
        }

        Seq()
      } else {
        Seq()
      }
    }
  }

  def step(current: IASTNode, state: State, direction: Direction): Seq[IASTNode] = {

    current match {
      case statement: IASTStatement =>
        Executor.parseStatement(statement, state, direction)
      case expression: IASTExpression =>
        Expressions.parse(expression, direction, state, state)
      case array: IASTArrayModifier =>
        if (direction == Exiting) {
          Seq()
        } else {
          if (array.getConstantExpression != null) {
            Seq(array.getConstantExpression)
          } else {
            Seq()
          }
        }
      case ptr: IASTPointer =>
        Seq()
      case param: IASTParameterDeclaration =>
        if (direction == Exiting && !state.isPreprocessing) {
          val paramInfo = param.getDeclarator.getName.resolveBinding().asInstanceOf[CParameter]
          
          if (!paramInfo.getType.isInstanceOf[IBasicType] || 
              paramInfo.getType.asInstanceOf[IBasicType].getKind != eVoid) {
            val arg = state.stack.pop
  
            val name = param.getDeclarator.getName.getRawSignature
            val newVar = new Variable(state, paramInfo.getType)
            
            state.setValue(TypeHelper.resolve(state, paramInfo.getType, arg), newVar.info)          
        
            state.vars.addVariable(name, newVar)
          }
          
          Seq()
        } else {
          Seq()
        }
      case tUnit: IASTTranslationUnit =>
        if (direction == Entering) {
          tUnit.getDeclarations
        } else {
          Seq()
        }
      case simple: IASTSimpleDeclaration =>
        if (direction == Entering) {
          simple.getDeclarators
        } else {
          Seq()
        }
      case fcnDec: IASTFunctionDeclarator =>
        if (direction == Entering) {
          fcnDec.getChildren.filter(x => !x.isInstanceOf[IASTName]).map { x => x }
        } else {
          Seq()
        }
      case decl: IASTDeclarator =>
        Executor.parseDeclarator(decl, direction, state)
      case fcnDef: IASTFunctionDefinition =>
        if (state.isPreprocessing) {
          state.functionMap += (fcnDef.getDeclarator.getName.getRawSignature -> fcnDef)
          Seq()
        } else if (direction == Exiting) {
          if (!state.vars.stack.isEmpty) {
            val retVal = state.vars.stack.head
            state.vars = state.executionContext.pop
            state.vars.stack.push(retVal)
          } else {
            state.vars = state.executionContext.pop
          }
          Seq()
        } else {
          Seq(fcnDef.getDeclarator, fcnDef.getBody)
        }
      case eq: IASTEqualsInitializer =>
        if (direction == Entering) {
          Seq(eq.getInitializerClause)
        } else {
          Seq()
        }
      case initList: IASTInitializerList =>
        if (direction == Entering) {
          initList.getClauses
        } else {
          Seq()
        }
      case typeId: IASTTypeId =>
        if (direction == Exiting) {
          val result = typeId.getDeclSpecifier match {
            case simple: IASTSimpleDeclSpecifier =>
              val isPointer = typeId.getAbstractDeclarator.getPointerOperators.size > 1
    
              import org.eclipse.cdt.core.dom.ast.IASTSimpleDeclSpecifier._
              
              simple.getType match {
                case `t_int` if isPointer => new CPointerType(new CBasicType(IBasicType.Kind.eInt, 0), 0)
                case `t_int` if simple.isShort() => new CBasicType(IBasicType.Kind.eChar16, 0)
                case `t_int`    => new CBasicType(IBasicType.Kind.eInt, 0)
                case `t_float`  => new CBasicType(IBasicType.Kind.eFloat, 0)
                case `t_double` => new CBasicType(IBasicType.Kind.eDouble, 0)           
                case `t_char`   => new CBasicType(IBasicType.Kind.eChar, 0)
                case `t_void`   => new CBasicType(IBasicType.Kind.eVoid, 0)
              }
            case simple: CASTTypedefNameSpecifier =>
              null
            case elab: CASTElaboratedTypeSpecifier =>
              elab.getName.resolveBinding().asInstanceOf[CStructure]
          }

          state.stack.push(result)
        }
        Seq()
      case spec: IASTSimpleDeclSpecifier =>
        if (direction == Entering) {
          Seq()
        } else {
          state.stack.push(spec.getRawSignature)
          Seq()
        }
    }
  }
}

class Executor(code: String) {

  val tUnit = Utils.getTranslationUnit(code)
  val engineState = new State
  
  var current: IASTNode = null
  var direction: Direction = Entering

  current = tUnit

  engineState.executionContext.push(new FunctionExecutionContext(Map(), null)) // load initial stack
  engineState.vars = engineState.executionContext.head

  execute()
  engineState.isPreprocessing = false
  engineState.stack.clear

  println("_----------------------------------------------_")

  engineState.globals ++= engineState.vars.varMap

  engineState.executionContext.clear
  engineState.executionContext.push(new FunctionExecutionContext(engineState.globals, null)) // load initial stack
  engineState.vars = engineState.executionContext.head
  engineState.vars.pathStack.clear
  engineState.vars.pathStack.push(engineState.functionMap("main"))
  current = engineState.vars.pathStack.head

  def tick(): Unit = {
    direction = if (engineState.vars.visited.contains(current)) Exiting else Entering

    //println(current.getClass.getSimpleName + ":" + direction)
    
    var paths: Seq[IASTNode] = Executor.step(current, engineState, direction) 
    
    if (engineState.isBreaking) {
      // unroll the path stack until we meet the first parent which is a loop
      var reverse = engineState.vars.pathStack.pop
      while (!reverse.isInstanceOf[IASTWhileStatement] && !reverse.isInstanceOf[IASTForStatement] && !reverse.isInstanceOf[IASTSwitchStatement]) {
        reverse = engineState.vars.pathStack.pop
      }

      engineState.isBreaking = false
    }


    if (engineState.isContinuing) {
      // unroll the path stack until we meet the first parent which is a loop

      var last: IASTNode = null
      last = engineState.vars.pathStack.pop
      while (!last.isInstanceOf[IASTForStatement]) {
        last = engineState.vars.pathStack.pop
      }

      val forLoop = last.asInstanceOf[IASTForStatement]

      engineState.vars.pathStack.push(forLoop)
      engineState.vars.pathStack.push(forLoop.getConditionExpression)
      engineState.vars.pathStack.push(forLoop.getIterationExpression)

      engineState.isContinuing = false
    }
    
    if (engineState.isReturning) {
      var last: IASTNode = null
      while (engineState.vars.pathStack.size > 1 && !last.isInstanceOf[IASTFunctionDefinition]) {
        last = engineState.vars.pathStack.pop
      }

      current = engineState.vars.pathStack.head
      engineState.isReturning = false
    } else {

      if (direction == Exiting) {
        engineState.vars.pathStack.pop
      } else {
        engineState.vars.visited += current
      }
  
      paths.reverse.foreach { path => engineState.vars.pathStack.push(path) }
  
      if (!engineState.vars.pathStack.isEmpty) {
        current = engineState.vars.pathStack.head
      } else {
        current = null
      }
    }
  }

  def execute() = {
    while (current != null) {
      tick()
    }
  }
}
