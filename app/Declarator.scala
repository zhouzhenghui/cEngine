package app.astViewer

import org.eclipse.cdt.core.dom.ast.IASTBinaryExpression.op_assign
import org.eclipse.cdt.core.dom.ast._
import org.eclipse.cdt.internal.core.dom.parser.c.{CASTDeclarator, CBasicType, CStructure, CTypedef}

object Declarator {

  def parse(decl: IASTDeclarator, direction: Direction)(implicit state: State): Seq[IASTNode] = {
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
        val theType = TypeHelper.stripSyntheticTypeInfo(nameBinding.asInstanceOf[IVariable].getType)

        val name = decl.getName.getRawSignature

        decl match {
          case arrayDecl: IASTArrayDeclarator =>

            val dimensions = arrayDecl.getArrayModifiers.filter{_.getConstantExpression != null}.map{dim => state.stack.pop match {
              // can we can assume dimensions are integers
              case ValueInfo(value, _) => value.asInstanceOf[Int]
              case info @ AddressInfo(_, _) => info.value.value.asInstanceOf[Int]
            }}

            val initializer = decl.getInitializer.asInstanceOf[IASTEqualsInitializer]

            // Oddly enough, it is possible to have a pointer to an array with no dimensions OR initializer:
            //    extern char *x[]

            if (dimensions.isEmpty && initializer != null) {

              if (TypeHelper.resolve(theType).getKind == IBasicType.Kind.eChar && !initializer.getInitializerClause.isInstanceOf[IASTInitializerList]) {
                // e.g. char str[] = "Hello!\n";
                val initString = state.stack.pop.asInstanceOf[StringLiteral].value

                val theArrayPtr = new ArrayVariable(state, theType.asInstanceOf[IArrayType], Seq(initString.size))

                val theStr = Utils.stripQuotes(initString)
                val translateLineFeed = theStr.replace("\\n", 10.asInstanceOf[Char].toString)
                val withNull = (translateLineFeed.toCharArray() :+ 0.toChar).map{char => ValueInfo(char.toByte, new CBasicType(IBasicType.Kind.eChar, 0))} // terminating null char

                theArrayPtr.setArray(withNull)
                state.context.addVariable(name, theArrayPtr)
              } else {
                val list = initializer.getInitializerClause.asInstanceOf[IASTInitializerList]
                val size = list.getSize

                val values: Array[ValueInfo] = (0 until size).map{x => state.stack.pop match {
                  case value @ ValueInfo(_,_) => value
                  case info @ AddressInfo(_,_) => info.value
                }}.reverse.toArray

                val theArrayPtr = new ArrayVariable(state, theType.asInstanceOf[IArrayType], Array(size))

                theArrayPtr.setArray(values)
                state.context.addVariable(name, theArrayPtr)
              }
            } else if (initializer != null) {
              val initVals: Array[Any] = (0 until initializer.getInitializerClause.getChildren.size).map{x => state.stack.pop}.reverse.toArray

              val theArrayVar: ArrayVariable = new ArrayVariable(state, theType.asInstanceOf[IArrayType], dimensions)

              val initialArray = initVals.map { newInit =>
                newInit match {
                  case StringLiteral(x) =>
                    state.createStringVariable(newInit.asInstanceOf[StringLiteral].value, false)
                  case info @ AddressInfo(_, _) => info.value
                  case value @ ValueInfo(_, _) => value
                }
              }

              state.setArray(initialArray, AddressInfo(theArrayVar.address + 4, theArrayVar.theType))
              state.context.addVariable(name, theArrayVar)
            } else {
              val theArrayVar = new ArrayVariable(state, theType.asInstanceOf[IArrayType], dimensions)
              state.context.addVariable(name, theArrayVar)
            }
          case decl: CASTDeclarator =>

            val stripped = TypeHelper.stripSyntheticTypeInfo(theType)

            val newVar = new Variable(state, theType)
            state.context.addVariable(name, newVar)

            if (!stripped.isInstanceOf[CStructure]) {
              val initVal = Option(decl.getInitializer).map(x => state.stack.pop).getOrElse(ValueInfo(0, null))
              BinaryExpr.parseAssign(op_assign, newVar, initVal)
            } else if (decl.getInitializer != null && decl.getInitializer.isInstanceOf[IASTEqualsInitializer]
              && decl.getInitializer.asInstanceOf[IASTEqualsInitializer].getInitializerClause.isInstanceOf[IASTInitializerList]) {

              val clause = decl.getInitializer.asInstanceOf[IASTEqualsInitializer].getInitializerClause
              val values = clause.asInstanceOf[IASTInitializerList].getClauses.map{x => state.stack.pop}.reverse.toList
              newVar.setValues(values)
            }
        }

        Seq()
      } else {
        Seq()
      }
    }
  }
}