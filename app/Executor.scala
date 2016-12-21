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

case class ValueInfo(value: AnyVal, theType: IType)
case class VarRef(name: String)

object Variable {                              
  def apply(x: Int): Int = x * 2
  def unapply(any: Any)(implicit state: State): Option[Any] = {
    if (any.isInstanceOf[VarRef]) {
      val ref = any.asInstanceOf[VarRef]
      if (state.context.containsId(ref.name)) {
        val resolved = state.context.resolveId(ref.name)
        Some(resolved)
      } else {
        None
      }
    } else {
      None
    }
  }
  
  def allocateSpace(state: State, aType: IType, numElements: Int): Address = {
    if (aType.isInstanceOf[CFunctionType]) {
      state.allocateSpace(4 * numElements)
    } else if (TypeHelper.isPointer(aType)) {
      state.allocateSpace(TypeHelper.sizeof(TypeHelper.pointerType))
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

case class StringLiteral(str: String) extends AnyVal



case class Address(value: Int) extends AnyVal {
  def +(x: Int) = {
    Address(value + x)
  }
}
case class AddressInfo(address: Address, theType: IType)

// A symbolic reference is a string that becomes something else, payload: X, after processing
// For most variables, this is an address

trait SymbolicReference {
  val theType: IType
  def allocate: Address
}

protected class ArrayVariable(state: State, theType: IType, dim: Seq[Int]) extends Variable(state, theType) {
 
  override def allocate: Address = {
    // where we store the actual data
    
    val dimensions = dim.reverse
    
    if (!dimensions.isEmpty) {      
      address = Variable.allocateSpace(state, TypeHelper.pointerType, 1)
      
      def recurse(subType: IType, dimensions: Seq[Int]): Address = {
  
        val addr = Variable.allocateSpace(state, TypeHelper.pointerType, dimensions.head)
        for (i <- (0 until dimensions.head)) {
          
          val sub = state.resolve(subType)
  
          if (dimensions.size > 1) {
            val subaddr = recurse(sub, dimensions.tail)
            state.setValue(subaddr.value, addr + i * 4)
          }
        }
        addr
      }
      
      recurse(state.resolve(theType), dimensions)
    } else {
      Address(0)
    }
  }
  
  
  
  val theArrayAddress = allocate
  state.setValue(theArrayAddress.value, info.address)

  def setArray(array: Array[_]): Unit = {
      state.setArray(array, AddressInfo(theArrayAddress, theType))
  }
  
  override def sizeof: Int = {
    val numElements = if (dim.isEmpty) 0 else dim.reduce{_ * _}
    TypeHelper.sizeof(theType) * numElements
  }
}

protected class Variable(val state: State, val theType: IType) extends SymbolicReference {

  var address = Address(0)
  var node: IASTNode = null
 // val payload = payload
  
  def allocate: Address = {
    address = Variable.allocateSpace(state, theType, 1)
    address
  }
  
  val size = TypeHelper.sizeof(theType)

  def info = AddressInfo(address, theType)
  
  def setValues(values: List[ValueInfo]) = {
     var offset = 0
      values.foreach { case ValueInfo(value, theType) =>
        state.setValue(value, address + offset)
        offset += TypeHelper.sizeof(theType)
      }
  }

  
  
  def value: ValueInfo = {
    ValueInfo(state.readVal(address, theType).value, theType)
  }
  
  def sizeof: Int = {
    TypeHelper.sizeof(theType)
  }
}

class ExecutionContext(parentVars: Map[String, Variable], val returnType: IType, state: State) {
  val visited = new ListBuffer[IASTNode]()
  val varMap = parentVars.clone()
  val pathStack = new Stack[IASTNode]()
  val stack = new Stack[Any]()

  def containsId(id: String) = varMap.contains(id) || state.hasFunction(id)
  def resolveId(id: String): Any = varMap.get(id).getOrElse(state.getFunction(id))
  def addVariable(id: String, theVar: Variable) = varMap += (id -> theVar) 
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

  def parseStatement(statement: IASTStatement, direction: Direction)(implicit state: State): Seq[IASTNode] = statement match {
    case breakStatement: IASTNullStatement =>
      Seq()
    case breakStatement: IASTBreakStatement =>
      state.isBreaking = true
      Seq()
    case continueStatement: IASTContinueStatement =>
      state.isContinuing = true
      Seq()
    case label: IASTLabelStatement =>
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

        val caseExpr = state.stack.pop.asInstanceOf[Literal].cast.value
        val switchExpr = state.stack.head
        
        val resolved = TypeHelper.resolve(switchExpr)

        if (caseExpr == resolved) {
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
          case x: Boolean => x
          case int: Int => int > 0
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
          case lit @ Literal(_) => lit.cast.value
          case x                => x
        }

        val shouldLoop = cast match {
          case ValueInfo(x: Int,_)     => x > 0
          case ValueInfo(x: Character,_)     => x > 0
          case x: Int     => x > 0
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
          case Variable(info: Variable) => info.value
          case lit @ Literal(_) =>
            lit.cast.value
          case x => x
        }

        val conditionResult = value match {
          case x: Int     => x > 0
          case ValueInfo(x: Int, _)     => x > 0
          case ValueInfo(x: Character, _)     => x > 0
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
          
          val result = TypeHelper.resolve(new CBasicType(IBasicType.Kind.eInt, 0), state.stack.pop).value
          
          result match {
            case bool: Boolean => bool
            case int: Int => int > 0
            case char: Character => char > 0
          }
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
            case lit @ Literal(_) if state.context.returnType != null => lit.typeCast(TypeHelper.resolve(state.context.returnType))
            case lit @ Literal(_) => lit.cast.value
            case Variable(info: Variable)       => 
              if (TypeHelper.isPointer(state.context.returnType)) {
                info.value.value
              } else {
                TypeHelper.cast(state.context.returnType, info.value.value)
              }
            case ValueInfo(theVal, _) => theVal
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
  
  def parseDeclarator(decl: IASTDeclarator, direction: Direction)(implicit state: State): Seq[IASTNode] = {
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
              // can we can assume dimensions are integers
              case lit: Literal => lit.cast.value.asInstanceOf[Int]
              case Variable(info: Variable) => info.value.value.asInstanceOf[Int]
              case int: Int => int
            }}
            
            val initializer = decl.getInitializer.asInstanceOf[IASTEqualsInitializer]
            
            // Oddly enough, it is possible to have a pointer to an array with no dimensions OR initializer:
            //    extern char *x[]
            
            if (dimensions.isEmpty && initializer != null) {       
              
              if (TypeHelper.resolve(theType).getKind == eChar && !initializer.getInitializerClause.isInstanceOf[IASTInitializerList]) {
                // char str[] = "Hello!\n";
                val initString = state.stack.pop.asInstanceOf[StringLiteral].str                
                val strAddr = state.createStringVariable(initString)
                val theArrayPtr = new Variable(state, theType.asInstanceOf[IArrayType])
                theArrayPtr.allocate
                state.setValue(strAddr.value, theArrayPtr.address)
                state.context.addVariable(name, theArrayPtr)
              } else {
                val list = initializer.getInitializerClause.asInstanceOf[IASTInitializerList]
                val size = list.getSize
                
                val values: Array[Any] = (0 until size).map{x => state.stack.pop match {
                  case lit: Literal => lit.cast
                  case int: Int => int
                  case ValueInfo(value,_) => value
                  case Variable(fcn: IASTFunctionDefinition) => fcn
                }}.reverse.toArray
  
                val theArrayPtr = new ArrayVariable(state, theType.asInstanceOf[IArrayType], Array(size))
                
                // for function pointers, dont set the array in the same way...
                if (!values.head.isInstanceOf[IASTFunctionDefinition]) {
                  theArrayPtr.setArray(values.toArray)
                }
                state.context.addVariable(name, theArrayPtr)
              }
            } else if (initializer != null) {
              val numElements = if (dimensions.isEmpty) 0 else dimensions.reduce{_ * _}
              val initialArray = new ListBuffer[Any]()

              val initVals: Array[Any] = (0 until initializer.getInitializerClause.getChildren.size).map{x => state.stack.pop}.reverse.toArray
              
              val theArrayPtr: ArrayVariable = new ArrayVariable(state, theType.asInstanceOf[IArrayType], dimensions)
              
              initVals.foreach { newInit =>
                if (newInit.isInstanceOf[StringLiteral]) {
                  initialArray += state.createStringVariable(newInit.asInstanceOf[StringLiteral].str)
                } else {
                  initialArray += newInit
                }
              }
              
              val resolvedType = if (initialArray.head.isInstanceOf[Address]) {
                TypeHelper.pointerType
              } else {
                theArrayPtr.info.theType
              }
              
              state.setArray(initialArray.toArray, AddressInfo(theArrayPtr.theArrayAddress, resolvedType))
              state.context.addVariable(name, theArrayPtr)
            } else {
              val initialArray = new ListBuffer[Any]()            
              val theArrayPtr = new ArrayVariable(state, theType.asInstanceOf[IArrayType], dimensions)             
              state.context.addVariable(name, theArrayPtr)
            }
          case decl: CASTDeclarator =>

            def createVariable(theType: IType, name: String): Variable = theType match {
              case struct: CStructure =>
                val newStruct = new Variable(state, theType)
                newStruct.allocate
                state.context.addVariable(name, newStruct)
                newStruct
              case typedef: CTypedef =>
                createVariable(typedef.getType, name)
              case qual: CQualifierType =>
                createVariable(qual.getType, name)
              case ptr: IPointerType =>
                val initVal = Option(decl.getInitializer).map(x => state.stack.pop).getOrElse(0)
                
                val newVar = initVal match {
                  case Variable(info: Variable) => 
                    val newVar = new Variable(state, theType)
                    newVar.allocate
                    state.setValue(info.value.value, newVar.address)
                    newVar
                  case AddressInfo(address, addrType) => 
                    val newVar = new Variable(state, theType)
                    newVar.allocate
                    if (TypeHelper.isPointer(addrType)) {
                      state.setValue(state.readVal(address, TypeHelper.pointerType).value, newVar.address)
                    } else {
                      state.setValue(address.value, newVar.address)
                    }
                    newVar
                  case int: Int => 
                    val newVar = new Variable(state, theType)
                    newVar.allocate
                    state.setValue(int, newVar.address)
                    newVar
                  case prim @ ValueInfo(_, newType) => 
                    val newVar = new Variable(state, theType)
                    newVar.allocate
                    state.setValue(prim.value, newVar.address)
                    newVar
                  case StringLiteral(str) =>
                    val newVar = new Variable(state, theType)
                    newVar.allocate
                    val strAddr = state.createStringVariable(str)
                    state.setValue(strAddr.value, newVar.address)
                    newVar
                  case lit @ Literal(_) => 
                    val newVar = new Variable(state, theType) // TODO: Not setting value? Whats going on here?
                    newVar.allocate
                    newVar
                  case Address(int) => 
                    val newVar = new Variable(state, theType) // TODO: Not setting value? Whats going on here?
                    newVar.allocate
                    newVar
                }
                
                state.context.addVariable(name, newVar)
                newVar               
               
              case basic: IBasicType =>
                val initVal = Option(decl.getInitializer).map(x => state.stack.pop).getOrElse(0)   

                val resolved = TypeHelper.resolve(theType, initVal).value

                val newVar = new Variable(state, theType)
                newVar.allocate
                val casted = TypeHelper.cast(newVar.info.theType, resolved).value
                state.setValue(casted, newVar.info.address)
                state.context.addVariable(name, newVar)
                newVar
            }

            val newVar = createVariable(theType, name)
            
            if (decl.getInitializer != null && decl.getInitializer.isInstanceOf[IASTEqualsInitializer]
                 && decl.getInitializer.asInstanceOf[IASTEqualsInitializer].getInitializerClause.isInstanceOf[IASTInitializerList]) {

              val fields = theType.asInstanceOf[CStructure].getFields
              val size = fields.size
              
              val values: Array[(ValueInfo, IType)] = fields.map{x => state.stack.pop.asInstanceOf[Literal].cast}.reverse zip fields.map(_.getType)
              val valueInfos = values.map{x => ValueInfo(x._1.value, x._2)}.toList
              newVar.setValues(valueInfos)
            }
        }

        Seq()
      } else {
        Seq()
      }
    }
  }

  def step(current: IASTNode, direction: Direction)(implicit state: State): Seq[IASTNode] = {

    current match {
      case statement: IASTStatement =>
        Executor.parseStatement(statement, direction)
      case expression: IASTExpression =>
        Expressions.parse(expression, direction)
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
        if (direction == Exiting) {
          val paramInfo = param.getDeclarator.getName.resolveBinding().asInstanceOf[CParameter]
          
          // ignore main's params for now
          val isInMain = param.getParent.isInstanceOf[IASTFunctionDeclarator] && 
                            param.getParent.asInstanceOf[IASTFunctionDeclarator].getName.getRawSignature == "main"
          val isInFunctionPrototype = Utils.getAncestors(param).exists{_.isInstanceOf[IASTSimpleDeclaration]}
          if (!isInMain && paramInfo != null && paramInfo.getType != null && !isInFunctionPrototype && (!paramInfo.getType.isInstanceOf[IBasicType] || 
              paramInfo.getType.asInstanceOf[IBasicType].getKind != eVoid)) {

            val arg = state.stack.pop
  
            val name = param.getDeclarator.getName.getRawSignature
            val newVar = new Variable(state, paramInfo.getType)
            newVar.allocate
            val resolved = TypeHelper.resolve(paramInfo.getType, arg).value
            val casted = TypeHelper.cast(newVar.info.theType, resolved).value
            state.setValue(casted, newVar.info.address)          
        
            state.context.addVariable(name, newVar)
          }
          
          Seq()
        } else {
          Seq()
        }
      case tUnit: IASTTranslationUnit =>
        if (direction == Entering) {
          tUnit.getChildren.filterNot{_.isInstanceOf[IASTFunctionDefinition]}
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
        Executor.parseDeclarator(decl, direction)
      case fcnDef: IASTFunctionDefinition =>
        if (direction == Exiting) {
          if (!state.context.stack.isEmpty) {
            val retVal = state.context.stack.head
            if (fcnDef.getDeclarator.getName.getRawSignature != "main") {
              state.functionContexts.pop
              if (fcnDef.getDeclSpecifier.getRawSignature == "void") {
                // if theres no return value, we can clear the stack
                state.stack.clear
              }
            }
            state.context.stack.push(retVal)
          } else {
            if (fcnDef.getDeclarator.getName.getRawSignature != "main") {
              state.functionContexts.pop
              if (fcnDef.getDeclSpecifier.getRawSignature == "void") {
                // if theres no return value, we can clear the stack
                state.stack.clear
              }
            }
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
              import org.eclipse.cdt.core.dom.ast.IASTSimpleDeclSpecifier._
              
              simple.getType match {
                case `t_int`    => new CBasicType(IBasicType.Kind.eInt, 0)
                case `t_float`  => new CBasicType(IBasicType.Kind.eFloat, 0)
                case `t_double` => new CBasicType(IBasicType.Kind.eDouble, 0)           
                case `t_char`   => new CBasicType(IBasicType.Kind.eChar, 0)
                case `t_void`   => new CBasicType(IBasicType.Kind.eVoid, 0)
                case `t_typeof`   => new CBasicType(IBasicType.Kind.eVoid, 0) // FIX
              }
            case simple: CASTTypedefNameSpecifier =>
              null
            case elab: CASTElaboratedTypeSpecifier =>
              elab.getName.resolveBinding().asInstanceOf[CStructure]
          }

          state.stack.push(result)
        }
        Seq()
    }
  }
}

class Executor() {
  
  var tUnit: IASTTranslationUnit = null
  
  def init(code: String, reset: Boolean, engineState: State) = {
    tUnit = Utils.getTranslationUnit(code)
    current = tUnit

    if (reset) {
      engineState.functionContexts.push(new ExecutionContext(Map(), null, engineState)) // load initial stack
    }

    execute(engineState)
    
     val fcns = tUnit.getChildren.collect{case x:IASTFunctionDefinition => x}.filter(_.getDeclSpecifier.getStorageClass != IASTDeclSpecifier.sc_extern)
    fcns.foreach{fcnDef => engineState.addFunctionDef(fcnDef)}

    engineState.context.pathStack.clear
    engineState.context.pathStack.push(engineState.getFunction("main"))
    current = engineState.context.pathStack.head
  }

  var current: IASTNode = null
  var direction: Direction = Entering

  def tick(engineState: State): Unit = {
    direction = if (engineState.context.visited.contains(current)) Exiting else Entering

    //println(current.getClass.getSimpleName + ":" + direction)
    
    var paths: Seq[IASTNode] = Executor.step(current, direction)(engineState)
    
    if (engineState.isBreaking) {
      // unroll the path stack until we meet the first parent which is a loop
      var reverse = engineState.context.pathStack.pop
      while (!reverse.isInstanceOf[IASTWhileStatement] && !reverse.isInstanceOf[IASTForStatement] && !reverse.isInstanceOf[IASTSwitchStatement]) {
        reverse = engineState.context.pathStack.pop
      }

      engineState.isBreaking = false
    }


    if (engineState.isContinuing) {
      // unroll the path stack until we meet the first parent which is a loop

      var last: IASTNode = null
      last = engineState.context.pathStack.pop
      while (!last.isInstanceOf[IASTForStatement]) {
        last = engineState.context.pathStack.pop
      }

      val forLoop = last.asInstanceOf[IASTForStatement]

      engineState.context.pathStack.push(forLoop)
      engineState.context.pathStack.push(forLoop.getConditionExpression)
      engineState.context.pathStack.push(forLoop.getIterationExpression)

      engineState.isContinuing = false
    }
    
    if (engineState.isReturning) {
      var last: IASTNode = null
      while (engineState.context.pathStack.size > 1 && !last.isInstanceOf[IASTFunctionDefinition]) {
        last = engineState.context.pathStack.pop
      }

      current = engineState.context.pathStack.head
      engineState.isReturning = false
    } else {

      if (direction == Exiting) {
        engineState.context.pathStack.pop
      } else {
        engineState.context.visited += current
      }
  
      paths.reverse.foreach { path => engineState.context.pathStack.push(path) }
  
      if (!engineState.context.pathStack.isEmpty) {
        current = engineState.context.pathStack.head
      } else {
        current = null
      }
    }
  }

  def execute(engineState: State) = {
    while (current != null) {
      tick(engineState)
    }
  }
}
