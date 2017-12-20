package scala.c.engine
package ast

import org.eclipse.cdt.core.dom.ast._
import org.eclipse.cdt.internal.core.dom.parser.c._

import scala.annotation.switch

object Expressions {

  def evaluate(expr: IASTInitializerClause)(implicit state: State): Option[ValueType] = expr match {
    case exprList: IASTExpressionList =>
      exprList.getExpressions.map{x => evaluate(x)}.last
    case ternary: IASTConditionalExpression =>
      val result = TypeHelper.resolveBoolean (evaluate(ternary.getLogicalConditionExpression).get)

      if (result) {
        evaluate(ternary.getPositiveResultExpression)
      } else {
        evaluate(ternary.getNegativeResultExpression)
      }
    case cast: IASTCastExpression =>
        val theType = TypeHelper.getType(cast.getTypeId).theType
        val operand = evaluate(cast.getOperand).get

        Some(operand match {
          case str @ StringLiteral(_) => str
          case LValue(addr, aType) =>
            theType match {
              case ptr: IPointerType if aType.isInstanceOf[IArrayType] =>
                val newAddr = state.allocateSpace(4)
                state.Stack.writeToMemory(addr, newAddr, theType)
                LValue(newAddr, theType)
              case _ => LValue(addr, theType)
            }
          case RValue(value, _) =>
            val newAddr = state.allocateSpace(TypeHelper.sizeof(theType))
            state.Stack.writeToMemory(TypeHelper.cast(theType, value).value, newAddr, theType)
            LValue(newAddr, theType)
        })
    case fieldRef: IASTFieldReference =>
        println("FIELD: " + fieldRef.getRawSignature)

        val struct = evaluate(fieldRef.getFieldOwner).get.asInstanceOf[LValue]

        val structType = TypeHelper.resolveStruct(struct.theType)

        val baseAddr = if (fieldRef.isPointerDereference) {
          state.readPtrVal(struct.address)
        } else {
          struct.address
        }

        var offset = 0
        var resultAddress: LValue = null

        structType.getKey match {
          case ICompositeType.k_struct =>
            structType.getFields.foreach{field =>
              if (field.getName == fieldRef.getFieldName.getRawSignature) {
                // can assume names are unique
                resultAddress = LValue(baseAddr + offset, field.getType)
              } else {
                offset += TypeHelper.sizeof(field)
              }
            }
          case ICompositeType.k_union =>
            structType.getFields.find{field => field.getName == fieldRef.getFieldName.getRawSignature}.foreach { field =>
              resultAddress = LValue(baseAddr, field.getType)
            }
        }

      println("FIELD RESULT: " + resultAddress.address)

        Some(resultAddress)
    case subscript: IASTArraySubscriptExpression =>

      println("SUBSCRIPT: " + subscript.getRawSignature)

      val arrayVarPtr = evaluate(subscript.getArrayExpression).head.asInstanceOf[LValue]
      val index = evaluate(subscript.getArgument).get match {
        case x @ RValue(_, _) => TypeHelper.cast(TypeHelper.pointerType, x.value).value.asInstanceOf[Int]
        case x @ LValue(_, _) => TypeHelper.cast(TypeHelper.pointerType, x.value.value).value.asInstanceOf[Int]
      }

      val aType = TypeHelper.getPointerType(arrayVarPtr.theType)
      val offset = arrayVarPtr.theType match {
        case x: IArrayType if x.getType.isInstanceOf[IArrayType] =>
          state.readPtrVal(arrayVarPtr.address + index * 4)
        case x: IPointerType  =>
          state.readPtrVal(arrayVarPtr.address) + index * TypeHelper.sizeof(aType)
        case _ =>
          arrayVarPtr.address + index * TypeHelper.sizeof(aType)
      }

      // state.readPtrVal(

      Some(LValue(offset, aType))
    case unary: IASTUnaryExpression =>
      Some(UnaryExpression.execute(evaluate(unary.getOperand).head, unary))
    case lit: IASTLiteralExpression =>
        val litStr = lit.getRawSignature
        Some(Literal.cast(lit.getRawSignature))
    case id: IASTIdExpression =>
        Some(state.context.resolveId(id.getName).get)
    case typeExpr: IASTTypeIdExpression =>
      // used for sizeof calls on a type
        val theType = TypeHelper.getType(typeExpr.getTypeId).theType
        Some(RValue(TypeHelper.sizeof(theType), TypeHelper.pointerType))
    case call: IASTFunctionCallExpression =>
        val pop = evaluate(call.getFunctionNameExpression).head

        val name = if (state.hasFunction(call.getFunctionNameExpression.getRawSignature)) {
          call.getFunctionNameExpression.getRawSignature
        } else {
          val info = pop.asInstanceOf[LValue]
          val resolved = TypeHelper.stripSyntheticTypeInfo(info.theType)
          resolved match {
            case ptr: IPointerType => state.getFunctionByIndex(info.value.value.asInstanceOf[Int]).name
          }
        }

        val args = call.getArguments.map{x => evaluate(x).head}

        state.callTheFunction(name, call, args, None)
    case bin: IASTBinaryExpression =>
      val result = (bin.getOperator, evaluate(bin.getOperand1).head) match {
        case (IASTBinaryExpression.op_logicalOr, op1 @ RValue(x: Boolean, _)) if x => op1
        case (IASTBinaryExpression.op_logicalAnd, op1 @ RValue(x: Boolean, _)) if !x => op1
        case (_, op1) =>
          val op2 = evaluate(bin.getOperand2).head

          val result = if (Utils.isAssignment(bin.getOperator)) {
            BinaryExpr.parseAssign(bin, bin.getOperator, op1.asInstanceOf[LValue], op2)
          } else {
            BinaryExpr.evaluate(bin, op1, op2, bin.getOperator)
          }

          result
      }

      Some(result)
  }
}