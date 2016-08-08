package scala.astViewer

import org.eclipse.cdt.core.dom.ast.IASTBinaryExpression._
import org.eclipse.cdt.core.dom.ast.{IASTEqualsInitializer, _}

import scala.astViewer.{IntPrimitive, Path, Utils}
import scala.collection.mutable.{ListBuffer, Stack}
import scala.util.control.Exception.allCatch



trait PrimitiveType
case class IntPrimitive(name: String, value: Long) extends PrimitiveType

class Scope(outerScope: Scope) {
  val integers = new ListBuffer[IntPrimitive]()

  def getVariableValue(name: String): String = {
    if (outerScope != null) {
      (outerScope.integers ++ integers).filter(_.name == name).head.value.toString
    } else {
      integers.filter(_.name == name).head.value.toString
    }
  }
}

case class Context(startNode: IASTNode) {
  var currentPath: Path = null
  val stack = new Stack[Any]()
  val variableMap = scala.collection.mutable.Map[String, Any]()
  val path = Utils.getPath(startNode)
}

class Executor(code: String) {

  val tUnit = Utils.getTranslationUnit(code)

  val stdout = new ListBuffer[String]()

  val mainContext = new Context(tUnit)

  val functionMap = scala.collection.mutable.Map[String, Path]()

  var functionReturnStack = new Stack[Path]()
  val functionArgumentMap = scala.collection.mutable.Map[String, Any]()

  var isVarInitialized = false
  var arraySize = 0

  def isLongNumber(s: String): Boolean = (allCatch opt s.toLong).isDefined

  def isDoubleNumber(s: String): Boolean = (allCatch opt s.toDouble).isDefined

  def prestep(current: Path): Unit = {

    val direction = current.direction

    current.node match {
      case array: IASTArrayModifier =>
      case fcnDef: IASTFunctionDefinition =>
        functionMap += (fcnDef.getDeclarator.getName.getRawSignature -> mainContext.currentPath)
        jumpToExit(mainContext)
      case fcnDecl: IASTFunctionDeclarator =>
      case decl: IASTDeclarator =>
        parseDeclarator(decl, direction, mainContext)
      case eq: IASTEqualsInitializer =>
        parseEqualsInitializer(eq, mainContext)
      case bin: IASTBinaryExpression =>
        val result = parseBinaryExpr(bin, direction, mainContext)
        if (result != null) {
          mainContext.stack.push(result)
        }
      case _ =>
    }
  }



  def printf(args: Array[IASTInitializerClause], context: Context) = {
    val formatString = args(0).getRawSignature.replaceAll("^\"|\"$", "")
    var currentArg = 1

    def getNumericArg() = {
      val arg = args(currentArg).getRawSignature
      val result = if (args(currentArg).isInstanceOf[IASTLiteralExpression]) {
        arg
      } else if (args(currentArg).isInstanceOf[IASTBinaryExpression] || args(currentArg).isInstanceOf[IASTFunctionCallExpression]) {
        // the argument is an expression
        context.stack.pop.toString
      } else {
        // the argument is just a variable reference
        context.variableMap(arg).toString
      }
      currentArg += 1
      result
    }

    def getStringArg() = {
      val arg = args(currentArg).getRawSignature.replaceAll("^\"|\"$", "")
      currentArg += 1
      arg
    }

    val result = formatString.split("""%d""").reduce{_ + getNumericArg + _}
      .split("""%s""").reduce{_ + getStringArg + _}
      .split("""%f""").reduce{_ + getNumericArg + _}


    result.split("""\\n""").foreach(line => stdout += line)
  }

  def parseStatement(statement: IASTStatement, context: Context, direction: Direction): Seq[IASTNode] = statement match {
    case ifStatement: IASTIfStatement =>
      Seq()
    case ret: IASTReturnStatement =>
      ret.getReturnValue match {
        case lit: IASTLiteralExpression =>
          context.stack.push(lit.getRawSignature)
        case _ =>
      }
      Seq()
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

  def parseExpression(expr: IASTExpression, direction: Direction, context: Context): Seq[IASTNode] = expr match {
    case subscript: IASTArraySubscriptExpression =>
      Seq()
    case unary: IASTUnaryExpression =>
      if (direction == Entering) {
        Seq(unary.getOperand)
      } else {
        Seq()
      }
    case lit: IASTLiteralExpression =>
      Seq()
    case id: IASTIdExpression =>
      Seq()
    case call: IASTFunctionCallExpression =>
      // only evaluate after leaving
      if (direction == Exiting) {
        val name = call.getFunctionNameExpression match {
          case x: IASTIdExpression => x.getName.getRawSignature
          case _ => "Error"
        }
        val args = call.getArguments

        if (name == "printf") {
          printf(args, context)
        } else {
          functionReturnStack.push(context.currentPath)
          context.currentPath = functionMap(name)

          // push the arguments onto the stack before calling
          args.foreach{ arg =>
            arg match {
              case x: IASTLiteralExpression =>
                context.stack.push(arg.getRawSignature.toInt)
              case _ =>
            }
          }
        }
        Seq()
      } else {
        call.getArguments
      }

    case bin: IASTBinaryExpression =>
      if (direction == Exiting) {
        val result = parseBinaryExpr(bin, direction, context)
        if (result != null) {
          context.stack.push(result)
        }
        Seq()
      } else {
        Seq(bin.getOperand1, bin.getOperand2)
      }
  }

  def step(current: IASTNode, context: Context, direction: Direction): Seq[IASTNode] = {

    current match {
      case statement: IASTStatement =>
        parseStatement(statement, context, direction)
      case expression: IASTExpression =>
        parseExpression(expression, direction, context)
      case array: IASTArrayModifier =>
        arraySize = array.getConstantExpression.getRawSignature.toInt
        Seq()
      case param: IASTParameterDeclaration =>
        if (direction == Exiting) {
          val arg = context.stack.pop
          functionArgumentMap += (param.getDeclarator.getName.getRawSignature -> arg)
        }
        Seq()
      case tUnit: IASTTranslationUnit =>
        Seq()
      case simple: IASTSimpleDeclaration =>
        if (direction == Entering) {
          simple.getDeclarators
        } else {
          Seq()
        }
      case fcnDec: IASTFunctionDeclarator =>
        Seq()
      case decl: IASTDeclarator =>
        parseDeclarator(decl, direction, context)
      case fcnDef: IASTFunctionDefinition =>
        if (direction == Exiting) {
          if (!functionReturnStack.isEmpty) {
            // We are exiting a function we're currently executing
            context.currentPath = functionReturnStack.pop
            functionArgumentMap.clear
          }
          Seq()
        } else {
          Seq(fcnDef.getDeclarator, fcnDef.getBody)
        }
      case decl: IASTSimpleDeclaration =>
        Seq()
      case eq: IASTEqualsInitializer =>
        parseEqualsInitializer(eq, context)
        if (direction == Entering) {
          Seq(eq.getInitializerClause)
        } else {
          Seq()
        }
    }
  }

  def parseDeclarator(decl: IASTDeclarator, direction: Direction, context: Context): Seq[IASTNode] = {
    if ((direction == Exiting || direction == Visiting) && !decl.getParent.isInstanceOf[IASTParameterDeclaration]) {
      var value: Any = null // init to zero
      if (isVarInitialized) {
        value = context.stack.pop
      }
      if (arraySize > 0) {
        context.variableMap += (decl.getName.getRawSignature -> Array.fill(arraySize)(0))
      } else {
        //println("ADDING GLOBAL VAR: " + decl.getName.getRawSignature + ", " + value)
        context.variableMap += (decl.getName.getRawSignature -> value)
      }
      Seq()
    } else {
      arraySize = 0
      isVarInitialized = false
      if (decl.getInitializer != null) {
        Seq(decl.getInitializer)
      } else {
        Seq()
      }
    }

  }

  def parseEqualsInitializer(eq: IASTEqualsInitializer, context: Context) = {
    isVarInitialized = true
    eq.getInitializerClause match {
      case lit: IASTLiteralExpression =>
        context.stack.push(castLiteral(lit))
      case _ => // dont do anything
    }
  }

  def castLiteral(lit: IASTLiteralExpression): Any = {
    val string = lit.getRawSignature
    if (isLongNumber(string)) {
      string.toInt
    } else {
      string.toDouble
    }
  }

  def parseBinaryOperand(op: IASTExpression, context: Context): Any = {
    op match {
      case lit: IASTLiteralExpression => castLiteral(lit)
      case id: IASTIdExpression => {
        if (context.variableMap.contains(id.getRawSignature)) {
          context.variableMap(id.getRawSignature)
        } else {
          functionArgumentMap(id.getRawSignature)
        }
      }
      case sub: IASTArraySubscriptExpression =>
        context.variableMap(sub.getArrayExpression.getRawSignature).asInstanceOf[Array[_]](sub.getArgument.getRawSignature.toInt)
      case bin: IASTBinaryExpression => context.stack.pop
      case bin: IASTUnaryExpression => context.stack.pop
      case fcn: IASTFunctionCallExpression => context.stack.pop
    }
  }

  def parseBinaryExpr(binaryExpr: IASTBinaryExpression, direction: Direction, context: Context): Any = {
    if (direction == Exiting || direction == Visiting) {

      val op1 = parseBinaryOperand(binaryExpr.getOperand1, context)
      val op2 = parseBinaryOperand(binaryExpr.getOperand2, context)

      binaryExpr.getOperator match {
        case `op_multiply` =>
          (op1, op2) match {
            case (x: Int, y: Int) =>
              x * y
            case (x: Double, y: Int) =>
              x * y
            case (x: Int, y: Double) =>
              x * y
            case (x: Double, y: Double) =>
              x * y
          }
        case `op_plus` =>
          (op1, op2) match {
            case (x: Int, y: Int) =>
              x + y
            case (x: Double, y: Int) =>
              x + y
            case (x: Int, y: Double) =>
              x + y
            case (x: Double, y: Double) =>
              x + y
          }
        case `op_minus` =>
          (op1, op2) match {
            case (x: Int, y: Int) =>
              x - y
            case (x: Double, y: Int) =>
              x - y
            case (x: Int, y: Double) =>
              x - y
            case (x: Double, y: Double) =>
              x - y
          }
        case `op_divide` =>
          (op1, op2) match {
            case (x: Int, y: Int) =>
              x / y
            case (x: Double, y: Int) =>
              x / y
            case (x: Int, y: Double) =>
              x / y
            case (x: Double, y: Double) =>
              x / y
          }
        case `op_assign` =>
          context.variableMap += (binaryExpr.getOperand1.getRawSignature -> op2)
          null
        case _ => null
      }
    } else {
      null
    }
  }

  def jumpToExit(context: Context) = {
    val start = context.currentPath
    if (start.direction == Entering) {
      while ( context.currentPath.node != start.node ||  context.currentPath.direction != Exiting) {
        context.currentPath = context.path( context.currentPath.index + 1)
      }
    } else {
      throw new Exception("Cannot jump if not entering")
    }
  }

  def execute = {

    var isDonePreprocessing = false
    mainContext.currentPath = mainContext.path.head

    while (!isDonePreprocessing) {
      if (mainContext.currentPath.index == mainContext.path.size - 1) {
        isDonePreprocessing = true
      } else {
        prestep(mainContext.currentPath)
        mainContext.currentPath = mainContext.path(mainContext.currentPath.index + 1)
      }
    }

    mainContext.currentPath = functionMap("main") // start from main
    mainContext.stack.clear

    def runProgram(current: IASTNode, direction: Direction): Unit = {
      //println(current.getClass.getSimpleName)
      // while there is still an execution context to run
      val newPaths = step(current, mainContext, direction)

      if (!newPaths.isEmpty) {
        newPaths.foreach { x => runProgram(x, Entering) }
        newPaths.reverse.foreach { x => runProgram(x, Exiting) }
      }
    }

    runProgram(functionMap("main").node, Entering)
  }
}
