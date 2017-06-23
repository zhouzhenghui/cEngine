package c.engine
package ast

import org.eclipse.cdt.core.dom.ast._
import org.eclipse.cdt.internal.core.dom.parser.c.{CBasicType, CFunctionType}
import org.eclipse.cdt.core.dom.ast.IASTUnaryExpression._
import org.eclipse.cdt.core.dom.ast.IBasicType.Kind._

object UnaryExpression {
  def execute(unary: IASTUnaryExpression)(implicit state: State) = {
    val one = RValue(1, new CBasicType(IBasicType.Kind.eInt, IBasicType.IS_UNSIGNED))
    val negativeOne = RValue(-1, new CBasicType(IBasicType.Kind.eInt, 0))

    def not(theVal: Any): AnyVal = theVal match {
      case info @ LValue(_, _) => not(info.value)
      case RValue(theVal, _) => not(theVal)
      case int: Int => if (int == 0) 1 else 0
      case long: Long => if (long == 0) 1 else 0
      case bool: Boolean => !bool
      case char: char => if (char == 0) 1 else 0
    }

    if (unary.getOperator != op_bracketedPrimary) {
      
      val value = state.stack.pop

      state.stack.push(unary.getOperator match {
        case `op_tilde` =>
          RValue(~value.asInstanceOf[RValue].value.asInstanceOf[Int], null)
        case `op_not` => RValue(not(value), one.theType)
        case `op_minus` =>
          val valueType = value
          val newVal = BinaryExpr.evaluate(unary, valueType, negativeOne, IASTBinaryExpression.op_multiply).value
          RValue(newVal, valueType.theType)
        case `op_postFixIncr` =>
          val lValue = value.asInstanceOf[LValue]
          val newVal = BinaryExpr.evaluate(unary, lValue, one, IASTBinaryExpression.op_plus).value

          val returnVal = RValue(lValue.value.value, lValue.theType)
          state.Stack.writeToMemory(newVal, lValue.address, lValue.theType)
          returnVal
        case `op_postFixDecr` =>
          val lValue = value.asInstanceOf[LValue]
          val newVal = BinaryExpr.evaluate(unary, lValue, one, IASTBinaryExpression.op_minus).value

          // push then set
          val returnVal = RValue(lValue.value.value, lValue.theType)
          state.Stack.writeToMemory(newVal, lValue.address, lValue.theType)
          returnVal
        case `op_prefixIncr` =>
          val lValue = value.asInstanceOf[LValue]
          val newVal = BinaryExpr.evaluate(unary, lValue, one, IASTBinaryExpression.op_plus).value

          // set then push
          state.Stack.writeToMemory(newVal, lValue.address, lValue.theType)
          RValue(newVal, lValue.theType)
        case `op_prefixDecr` =>
          val lValue = value.asInstanceOf[LValue]
          val newVal = BinaryExpr.evaluate(unary, lValue, one, IASTBinaryExpression.op_minus).value

          // set then push
          state.Stack.writeToMemory(newVal, lValue.address, lValue.theType)
          RValue(newVal, lValue.theType)
        case `op_sizeof` =>
          value match {
            case info@LValue(_, theType) => RValue(info.sizeof, TypeHelper.pointerType)
          }
        case `op_amper` =>
          value match {
            case info@LValue(_, _) =>
              info.theType match {
                case fcn: CFunctionType => LValue(info.address, fcn)
                case x: IType => RValue(info.address, TypeHelper.pointerType)
              }
          }
        case `op_star` =>
          value match {
            case RValue(int: Int, theType) =>
              LValue(int, TypeHelper.resolve(theType))
            case info@LValue(_, _) =>
              val nestedType = info.theType match {
                case ptr: IPointerType => ptr.getType
                case array: IArrayType => array.getType
              }

              if (!nestedType.isInstanceOf[IFunctionType]) {
                LValue(info.value.value.asInstanceOf[Int], nestedType)
              } else {
                // function pointers can ignore the star
                info
              }

          }
      })
    }
  }
}
