package phantm.CFG
import phantm._
import phantm.AST.{Trees => AST}
import phantm.CFG.{Trees => CFG}
import phantm.analyzer.Symbols._
import scala.collection.mutable.{Map,HashMap}

object ASTToCFG {

  /** Builds a control flow graph from a method declaration. */
  def convertAST(statements: List[AST.Statement], scope: Scope): ControlFlowGraph = {
    // Contains the entry+exit vertices for continue/break
    var gotoLabels = Map[String, Vertex]();
    var forwardGotos = Map[String, List[(Vertex, Positional)]]();

    var controlStack: List[(Vertex, Vertex)] = Nil
    var dispatchers: List[(Vertex, CFG.TempID)] = Nil

    val cfg = new ControlFlowGraph
    val assertionsEnabled: Boolean = true
    val retval: CFG.TempID = CFG.TempID("retval");

    type Vertex = cfg.Vertex

    /** Creates fresh variable names on demand. */
    object FreshName {
      var count = 0

      def apply(prefix: String): String = {
        val post = count
        count = count  + 1
        prefix + "#" + post
      }
    }

    /** Creates fresh variable tree nodes on demand. */
    object FreshVariable {
      //def apply(prefix: String, tpe: Type) = CFG.TempID(FreshName(prefix))
      def apply(prefix: String) = CFG.TempID(FreshName(prefix))
    }

    /** Helper to add edges and vertices to the nascent CFG. while maintaining
     * the current "program counter", that is, the point from which the rest
     * of the graph should be built. */
    object Emit {
      private var pc: Vertex = cfg.entry
      def getPC: Vertex = pc
      def setPC(v: Vertex) = { pc = v }

      // emits a statement between two program points
      def statementBetween(from: Vertex, stat: CFG.Statement, to : Vertex): Unit = {
        cfg += (from, stat, to)
      }
      
      // emits a statement from the current PC and sets the new PC after it
      def statement(stat: CFG.Statement): Unit = {
        val v = cfg.newVertex
        cfg += (pc, stat, v)
        setPC(v)
      }

      // emits a statement from the current PC to an existing program point
      def statementCont(stat: CFG.Statement, cont: Vertex) = {
        cfg += (pc, stat, cont)
      }

      // emits an ''empty'' statement (equivalent to unconditional branch) from the current PC to an existing point
      def goto(cont: Vertex) = {
        cfg += (pc, CFG.Skip, cont)
      }
    }

    val scopeClassSymbol : Option[ClassSymbol] = scope match {
        case cs: ClassSymbol =>
            Some(cs)
        case _ =>
            None
    }

    /** Generates the part of the graph corresponding to the branching on a conditional expression */
    def condExpr(ex: AST.Expression, falseCont: Vertex, trueCont: Vertex): Unit = {
      // should have been enforced by type checking.
      // assert(ex.getType == TBoolean)

      ex match {
          case AST.Exit(Some(value)) =>
            val retV = FreshVariable("exit").setPos(ex)
            Emit.statementCont(exprStore(retV, value), cfg.exit)
          case AST.Exit(None) =>
            val retV = FreshVariable("exit").setPos(ex)
            Emit.statementCont(CFG.Assign(retV, CFG.PHPLong(0)).setPos(ex), cfg.exit)
          case AST.BooleanAnd(lhs, rhs) =>
            val soFarTrueV = cfg.newVertex
            condExpr(lhs, falseCont, soFarTrueV)
            Emit.setPC(soFarTrueV)
            condExpr(rhs, falseCont, trueCont)
          case AST.BooleanOr(lhs, rhs) =>
            val soFarFalseV = cfg.newVertex
            condExpr(lhs, soFarFalseV, trueCont)
            Emit.setPC(soFarFalseV)
            condExpr(rhs, falseCont, trueCont)
          case AST.Equal(lhs, rhs) =>
            val e1 = expr(lhs)
            val e2 = expr(rhs)
            Emit.statementCont(CFG.Assume(e1, CFG.EQUALS, e2).setPos(ex), trueCont)
            Emit.statementCont(CFG.Assume(e1, CFG.NOTEQUALS, e2).setPos(ex), falseCont)
          case AST.Identical(lhs, rhs) =>
            val e1 = expr(lhs)
            val e2 = expr(rhs)
            Emit.statementCont(CFG.Assume(e1, CFG.IDENTICAL, e2).setPos(ex), trueCont)
            Emit.statementCont(CFG.Assume(e1, CFG.NOTIDENTICAL, e2).setPos(ex), falseCont)
          case AST.Smaller(lhs, rhs) =>
            val e1 = expr(lhs)
            val e2 = expr(rhs)
            Emit.statementCont(CFG.Assume(e1, CFG.LT, e2).setPos(ex), trueCont)
            Emit.statementCont(CFG.Assume(e1, CFG.GEQ, e2).setPos(ex), falseCont)
          case AST.SmallerEqual(lhs, rhs) =>
            val e1 = expr(lhs)
            val e2 = expr(rhs)
            Emit.statementCont(CFG.Assume(e1, CFG.LEQ, e2).setPos(ex), trueCont)
            Emit.statementCont(CFG.Assume(e1, CFG.GT, e2).setPos(ex), falseCont)
          case AST.PHPTrue() =>
            Emit.goto(trueCont)
          case AST.PHPFalse() =>
            Emit.goto(falseCont)
          case AST.PHPNull() =>
            Emit.goto(falseCont)
          case AST.PHPInteger(n) =>
            if (n == 0)
                Emit.goto(falseCont)
            else
                Emit.goto(trueCont)
          case AST.BooleanNot(not) =>
            condExpr(not, trueCont, falseCont)
          case _ =>
            val e = expr(ex)
            Emit.statementCont(CFG.Assume(e, CFG.EQUALS, CFG.PHPTrue().setPos(ex)).setPos(ex), trueCont)
            Emit.statementCont(CFG.Assume(e, CFG.NOTEQUALS, CFG.PHPTrue().setPos(ex)).setPos(ex), falseCont)
      }
    }
 
    /** Transforms a variable from the AST to one for the CFG.. */
    def varFromVar(v: AST.Variable): CFG.Variable = v match {
        case AST.SimpleVariable(id) => idFromId(id)
        case AST.VariableVariable(ex) => CFG.VariableVar(expr(ex)).setPos(v)
        case AST.ArrayEntry(array, index) => CFG.ArrayEntry(expr(array), expr(index)).setPos(v)
        case AST.NextArrayEntry(array) => CFG.NextArrayEntry(expr(array)).setPos(v)
        case AST.ObjectProperty(obj, property) => CFG.ObjectProperty(expr(obj), CFG.PHPString(property.value).setPos(property)).setPos(v)
        case AST.DynamicObjectProperty(obj, property) => CFG.ObjectProperty(expr(obj), expr(property)).setPos(v)
        case AST.ClassProperty(cl, property) =>
            // We try to resolve the class symbol, if so, we return a
            // static class property, otherwise it will be dynamic
            val res = (cl, property) match {
                case (AST.StaticClassRef(_, _, cid), AST.SimpleVariable(pid)) =>
                    cid.getSymbol match {
                        case cs: ClassSymbol =>
                            cs.lookupStaticProperty(pid.value, scopeClassSymbol) match {
                                case LookupResult(Some(ps), _, _) =>
                                    Some(CFG.ClassProperty(ps).setPos(v))
                                case _ =>
                                    if (Main.verbosity > 0) {
                                        Reporter.notice("Undefined class property '"+cid.value+"::$"+pid.value+"'", pid)
                                    }
                                    None
                            }
                        case _ =>
                            None
                    }
                case _ =>
                    Some(CFG.VariableClassProperty(cl, expr(property)).setPos(v))
            }

            res.getOrElse(CFG.NoVar().setPos(v))
    }

    /** Transforms an identifier from the AST to one for the CFG.. */
    def idFromId(id: AST.Identifier): CFG.Identifier = {
        // should be enforced by type checking and by construction
        id.getSymbol match {
            case vs: VariableSymbol =>
                CFG.Identifier(vs).setPos(id)
            case _ => error("Woooot?");
        }
    }
    
    /** If an expression can be translated without flattening, does it and
      * returns the result in a Some(...) instance. Otherwise returns None. */
    def alreadySimple(ex: AST.Expression): Option[CFG.SimpleValue] = ex match {
      case v: AST.Variable =>
        Some(varFromVar(v))
      case AST.Constant(id) =>
        Some(CFG.Constant(GlobalSymbols.lookupOrRegisterConstant(id)).setPos(ex))
      case AST.ClassConstant(c, i) =>
            c match {
                case AST.StaticClassRef(_, _, cid) =>
                    val occ = cid.getSymbol match {
                        case cs: ClassSymbol =>
                            cs.lookupConstant(i.value) match {
                                case Some(ccs) =>
                                    Some(CFG.ClassConstant(ccs).setPos(ex))
                                case None =>
                                    if (Main.verbosity > 0) {
                                        Reporter.notice("Undefined class constant '"+cid.value+"::"+i.value+"'", i)
                                    }
                                    None
                            }
                        case _ =>
                            if (Main.verbosity > 0) {
                                Reporter.notice("Undefined class '"+cid.value+"'", cid)
                            }
                            None
                    }

                    Some(occ.getOrElse(CFG.NoVar().setPos(ex)))
                case _ =>
                    Some(CFG.VariableClassConstant(c, i).setPos(ex))
            }
      case AST.PHPInteger(v) =>
        Some(CFG.PHPLong(v).setPos(ex))
      case AST.PHPFloat(v) =>
        Some(CFG.PHPFloat(v).setPos(ex))
      case AST.PHPString(v) =>
        Some(CFG.PHPString(v).setPos(ex))
      case AST.PHPTrue() =>
        Some(CFG.PHPTrue().setPos(ex))
      case AST.PHPFalse() =>
        Some(CFG.PHPFalse().setPos(ex))
      case AST.PHPNull() =>
        Some(CFG.PHPNull().setPos(ex))
      case AST.MCFile() =>
        Some(CFG.PHPString("__FILE__").setPos(ex))
      case AST.MCLine() =>
        Some(CFG.PHPLong(1).setPos(ex))
      case AST.MCDir() =>
        Some(CFG.PHPString("__DIR__").setPos(ex))
      case AST.MCClass() =>
        Some(CFG.PHPString("__CLASS__").setPos(ex))
      case AST.MCMethod() =>
        Some(CFG.PHPString("__METHOD__").setPos(ex))
      case AST.MCFunction() =>
        Some(CFG.PHPString("__FUNCTION__").setPos(ex))
      case AST.MCNamespace() =>
        Some(CFG.PHPString("__NAMESPACE__").setPos(ex))
      case _ => None
    }
 
    def notyet(ex: AST.Expression) = throw new Exception("Not yet implemented in CFG.: "+ex+"("+ex.getPos+")");

    // If the assignation can easily be done, do it already
    def exprStore(v: CFG.Variable, ex: AST.Expression): CFG.Statement = exprStoreGet(v, ex) match {
        case Some(stmt) => stmt.setPos(ex)
        case None => CFG.Assign(v, expr(ex)).setPos(ex);
    }

    def exprStoreGet(v: CFG.Variable, ex: AST.Expression): Option[CFG.Statement] = alreadySimple(ex) match {
        case Some(x) => Some(CFG.Assign(v, x))
        case None =>
            ex match {
                case AST.Clone(obj) =>
                    Some(CFG.Assign(v, CFG.Clone(expr(obj)).setPos(ex)))
                case AST.Plus(lhs, rhs) =>
                    Some(CFG.AssignBinary(v, expr(lhs), CFG.PLUS, expr(rhs)))
                case AST.Minus(lhs, rhs) =>
                    Some(CFG.AssignBinary(v, expr(lhs), CFG.MINUS, expr(rhs)))
                case AST.Div(lhs, rhs) =>
                    Some(CFG.AssignBinary(v, expr(lhs), CFG.DIV, expr(rhs)))
                case AST.Mult(lhs, rhs) =>
                    Some(CFG.AssignBinary(v, expr(lhs), CFG.MULT, expr(rhs)))
                case AST.Concat(lhs, rhs) =>
                    Some(CFG.AssignBinary(v, expr(lhs), CFG.CONCAT, expr(rhs)))
                case AST.Mod(lhs, rhs) =>
                    Some(CFG.AssignBinary(v, expr(lhs), CFG.MOD, expr(rhs)))
                case AST.BooleanXor(lhs, rhs) =>
                    Some(CFG.AssignBinary(v, expr(lhs), CFG.BOOLEANXOR, expr(rhs)))
                case AST.BitwiseAnd(lhs, rhs) =>
                    Some(CFG.AssignBinary(v, expr(lhs), CFG.BITWISEAND, expr(rhs)))
                case AST.BitwiseOr(lhs, rhs) =>
                    Some(CFG.AssignBinary(v, expr(lhs), CFG.BITWISEOR, expr(rhs)))
                case AST.BitwiseXor(lhs, rhs) =>
                    Some(CFG.AssignBinary(v, expr(lhs), CFG.BITWISEXOR, expr(rhs)))
                case AST.ShiftLeft(lhs, rhs) =>
                    Some(CFG.AssignBinary(v, expr(lhs), CFG.SHIFTLEFT, expr(rhs)))
                case AST.ShiftRight(lhs, rhs) =>
                    Some(CFG.AssignBinary(v, expr(lhs), CFG.SHIFTRIGHT, expr(rhs)))
                case AST.BooleanNot(rhs) =>
                    Some(CFG.AssignUnary(v, CFG.BOOLEANNOT, expr(rhs)))
                case AST.BitwiseNot(rhs) =>
                    Some(CFG.AssignUnary(v, CFG.BITSIWENOT, expr(rhs)))
                case AST.InstanceOf(lhs, cr) =>
                    Some(CFG.Assign(v, CFG.Instanceof(expr(lhs), cr).setPos(ex)))
                case AST.Ternary(cond, Some(then), elze) =>
                    Some(CFG.Assign(v, CFG.Ternary(expr(cond), expr(then), expr(elze)).setPos(ex)))
                case AST.Ternary(cond, None, elze) =>
                    Some(CFG.Assign(v, CFG.Ternary(expr(cond), v, expr(elze)).setPos(ex)))
                case AST.Silence(value) =>
                    Some(CFG.Assign(v, expr(value)))
                case AST.Execute(value) =>
                    Some(CFG.Assign(v, CFG.FunctionCall(internalFunction("shell_exec").setPos(ex), List(CFG.PHPString(value).setPos(ex))).setPos(ex)))
                case AST.Print(value) =>
                    Some(CFG.Assign(v, CFG.FunctionCall(internalFunction("print").setPos(ex), List(expr(value))).setPos(ex)))
                case AST.Eval(value) =>
                    Some(CFG.Assign(v, CFG.FunctionCall(internalFunction("eval").setPos(ex), List(expr(value))).setPos(ex)))
                case AST.Empty(va) =>
                    Some(CFG.Assign(v, CFG.FunctionCall(internalFunction("empty").setPos(ex), List(expr(va))).setPos(ex)))
                case AST.FunctionCall(AST.StaticFunctionRef(_, _, name), args) =>
                    Some(CFG.Assign(v, CFG.FunctionCall(name, args map { a => expr(a.value) }).setPos(ex)))
                case fc @ AST.FunctionCall(_, args) =>
                    Reporter.notice("Dynamic function call ignored", fc)
                    Some(CFG.Assign(v, CFG.PHPAny().setPos(fc)))
                case AST.MethodCall(obj, AST.StaticMethodRef(id), args) => 
                    Some(CFG.Assign(v, CFG.MethodCall(expr(obj), id, args.map {a => expr(a.value) }).setPos(ex)))
                case AST.MethodCall(obj, _, args) => 
                    Some(CFG.Assign(v, CFG.PHPAny().setPos(ex)))
                case AST.StaticMethodCall(cl, AST.StaticMethodRef(id), args) => 
                    Some(CFG.Assign(v, CFG.StaticMethodCall(cl, id, args.map {a => expr(a.value) }).setPos(ex)))
                case AST.Array(Nil) =>
                    Some(CFG.Assign(v, CFG.PHPEmptyArray()))
                case AST.New(cr, args) =>
                    Some(CFG.Assign(v, CFG.New(cr, args map { a => expr(a.value) })))
                case _ => 
                    None
            }
    }

    def internalFunction(name: String): AST.Identifier = {
        GlobalSymbols.lookupFunction(name) match {
            case Some(s) => AST.Identifier(name).setSymbol(s)
            case None => AST.Identifier(name);
        }
    }

    def expr(ex: AST.Expression): CFG.SimpleValue = alreadySimple(ex) match {
        case Some(x) => x
        case None => ex match {
            case _ =>
                var v: CFG.Variable = FreshVariable("expr").setPos(ex)
                var retval: Option[CFG.SimpleValue] = None
                exprStoreGet(v, ex) match {
                    case Some(stmt) => stmt.setPos(ex); Emit.statement(stmt)
                    case _ => ex match {
                        case AST.ExpandArray(vars, e) =>
                            val v = expr(e);
                            def assignListItem(vars: List[Option[AST.Variable]], from: CFG.SimpleValue): Unit = {
                                for((vv, i) <- vars.zipWithIndex) {
                                    vv match {
                                        case Some(vv: AST.ListVar) =>
                                            assignListItem(vv.vars, CFG.ArrayEntry(from, CFG.PHPLong(i).setPos(vv)).setPos(vv))
                                        case Some(vv) =>
                                            Emit.statement(CFG.Assign(varFromVar(vv), CFG.ArrayEntry(from, CFG.PHPLong(i).setPos(vv)).setPos(vv)).setPos(ex))
                                        case None =>
                                    }
                                }
                            }
                            assignListItem(vars, v)
                            retval = Some(v)
                        case AST.Assign(va, value, byref) =>
                            v = varFromVar(va);
                            Emit.statement(exprStore(v, value));
                        case AST.PreInc(vAST) =>
                            val vCFG = varFromVar(vAST)
                            Emit.statement(CFG.AssignBinary(vCFG, vCFG, CFG.PLUS, CFG.PHPLong(1)).setPos(ex))
                            v = vCFG;
                        case AST.PreDec(vAST) =>
                            val vCFG = varFromVar(vAST)
                            Emit.statement(CFG.AssignBinary(vCFG, vCFG, CFG.MINUS, CFG.PHPLong(1)).setPos(ex))
                            v = vCFG;
                        case AST.PostInc(vAST) =>
                            val vCFG = varFromVar(vAST)
                            Emit.statement(CFG.AssignBinary(v, vCFG, CFG.PLUS, CFG.PHPLong(1)).setPos(ex))
                            Emit.statement(CFG.AssignBinary(vCFG, vCFG, CFG.PLUS, CFG.PHPLong(1)).setPos(ex))
                        case AST.PostDec(vAST) =>
                            val vCFG = varFromVar(vAST)
                            Emit.statement(CFG.AssignBinary(v, vCFG, CFG.MINUS, CFG.PHPLong(1)).setPos(ex))
                            Emit.statement(CFG.AssignBinary(vCFG, vCFG, CFG.MINUS, CFG.PHPLong(1)).setPos(ex))
                        case _: AST.BooleanAnd | _: AST.BooleanOr | _: AST.Equal |
                             _: AST.Identical | _: AST.Smaller  | _: AST.SmallerEqual =>
                            val trueV = cfg.newVertex
                            val falseV = cfg.newVertex
                            condExpr(ex, falseV, trueV)
                            val afterV = cfg.newVertex
                            // We only add assigns edges if something flows in those vertices
                            if (cfg.inEdges(trueV).size != 0) {
                                Emit.statementBetween(trueV, CFG.Assign(v, CFG.PHPTrue().setPos(ex)).setPos(ex), afterV)
                            }
                            if (cfg.inEdges(falseV).size != 0) {
                                Emit.statementBetween(falseV, CFG.Assign(v, CFG.PHPFalse().setPos(ex)).setPos(ex), afterV)
                            }
                            Emit.setPC(afterV)
                        case AST.Isset(vs) =>
                            if (vs.length > 1) {
                                Emit.statement(CFG.Assign(v, CFG.FunctionCall(internalFunction("isset"), vs.map{expr(_)}).setPos(ex)).setPos(ex))
                            } else {
                                Emit.statement(CFG.Assign(v, CFG.FunctionCall(internalFunction("isset"), List(expr(vs.head))).setPos(ex)).setPos(ex))
                            }
                        case AST.Array(values) =>
                            Emit.statement(CFG.Assign(v, CFG.PHPEmptyArray().setPos(ex)).setPos(ex))
                            var i = 0;
                            for (av <- values) av match {
                                case (Some(x), va, byref) =>
                                    Emit.statement(CFG.Assign(CFG.ArrayEntry(v, expr(x)), expr(va)).setPos(ex))
                                case (None, va, byref) =>
                                    Emit.statement(CFG.Assign(CFG.ArrayEntry(v, CFG.PHPLong(i).setPos(v)).setPos(va), expr(va)).setPos(ex))
                                    i += 1
                            }

                        case AST.Constant(id) =>
                            Emit.statement(CFG.Assign(v, CFG.Constant(GlobalSymbols.lookupOrRegisterConstant(id)).setPos(ex)).setPos(ex))

                        case AST.ClassConstant(cl, id) =>
                            cl match {
                                // TODO
                                case _ =>
                                    Emit.statement(CFG.Assign(v, CFG.VariableClassConstant(cl, id).setPos(ex)).setPos(ex))
                            }

                        case AST.Cast(typ, e) =>
                            Emit.statement(CFG.Assign(v, CFG.Cast(typ, expr(e)).setPos(ex)).setPos(ex))

                        case AST.Block(sts) =>
                            val endblock = cfg.newVertex
                            stmts(sts, endblock)
                            Emit.setPC(endblock)
                            Emit.statement(CFG.Assign(v, CFG.PHPTrue()).setPos(ex))
                        case i: AST.Include =>
                            // ignore
                            retval = Some(CFG.PHPFalse().setPos(i))
                        case r: AST.Require =>
                            // ignore
                            retval = Some(CFG.PHPFalse().setPos(r))
                        case e: AST.Exit =>
                            Emit.goto(cfg.exit)
                            Emit.setPC(cfg.newVertex)
                            retval = Some(new CFG.NoVar().setPos(ex))

                        case _ => error("expr() not handling correctly: "+ ex +"("+ex.getPos+")")
                    }
                }
                retval match {
                    case Some(x) => x
                    case None => v
                }
            }
    }

    /** Emits a sequence of statements. */
    def stmts(sts: List[AST.Statement], cont: Vertex): Unit = sts match {
        case s::s2::sr =>
            val tmp = cfg.newVertex
            stmt(s, tmp)
            stmts(s2::sr, cont)
        case s::Nil =>
            stmt(s, cont)
        case Nil =>
            Emit.goto(cont)
    }

    /** Emits a single statement. cont = where to continue after the statement */
    def stmt(s: AST.Statement, cont: Vertex): Unit = { 
      s match {
        case AST.LabelDecl(name) =>
            val v = Emit.getPC

            gotoLabels(name.value) = Emit.getPC

            for ((l, p) <- forwardGotos.getOrElse(name.value, Nil)) {
                Emit.setPC(l);
                Emit.goto(v);
            }

            Emit.setPC(v)

            forwardGotos -= name.value

            Emit.goto(cont)
        case AST.Goto(AST.Label(name)) =>
            val v = Emit.getPC

            if (gotoLabels contains name.value) {
                Emit.goto(gotoLabels(name.value));
            } else {
                forwardGotos(name.value) = (v, s) :: forwardGotos.getOrElse(name.value, Nil)
            }

            Emit.setPC(cont)
        case AST.Block(sts) =>
            stmts(sts, cont)
        case AST.If(cond, then, elze) =>
            val thenV = cfg.newVertex
            val elzeV = cfg.newVertex
            cfg.openGroup("if", Emit.getPC)
            elze match {
                case Some(st) =>
                    condExpr(cond, elzeV, thenV)
                    Emit.setPC(elzeV)
                    stmt(st, cont);
                case None =>
                    condExpr(cond, cont, thenV)
            }
            Emit.setPC(thenV)
            stmt(then, cont)
            cfg.closeGroup(cont)
        case AST.While(cond, st) =>
            val beginV = Emit.getPC
            val beginWhileV = cfg.newVertex
            cfg.openGroup("while", Emit.getPC)
            condExpr(cond, cont, beginWhileV)
            Emit.setPC(beginWhileV)
            controlStack = (beginV, cont) :: controlStack
            stmt(st, beginV)
            controlStack = controlStack.tail
            cfg.closeGroup(cont)
        case AST.DoWhile(body, cond) =>
            val beginV = Emit.getPC
            val beginCheck = cfg.newVertex
            cfg.openGroup("doWhile", beginV)
            controlStack = (beginCheck, cont) :: controlStack
            stmt(body, beginCheck)
            condExpr(cond, cont, beginV)
            controlStack = controlStack.tail
            cfg.closeGroup(cont)
        case AST.For(init, cond, step, then) => 
            cfg.openGroup("for", Emit.getPC)
            val beginCondV = cfg.newVertex
            val beginBodyV = cfg.newVertex
            val beginStepV = cfg.newVertex
            stmt(init, beginCondV);
            Emit.setPC(beginCondV);
            condExpr(cond, cont, beginBodyV)
            Emit.setPC(beginBodyV);
            controlStack = (beginStepV, cont) :: controlStack
            stmt(then, beginStepV);
            controlStack = controlStack.tail
            Emit.setPC(beginStepV);
            stmt(step, beginCondV);
            cfg.closeGroup(cont)
        case AST.Throw(ex) =>
            Emit.goto(cont)
        case AST.Try(body, catches) =>
            // For now, we execute the body and ignore the catches
            stmt(body, cont)
            /*
            val dispatchExceptionV = cfg.newVertex
            val ex = FreshVariable("exception")
            dispatchers = (dispatchExceptionV, ex) :: dispatchers



            // First, execute the body
            Emit.setPC(beginTryV)
            stmt(body)
            Emit.goto(cont)

            // We define the dispatcher based on the catch conditions
            val nextCatchV = cfg.newVertex
            val beginCatchV = cfg.newVertex
            for (c <- catches) c match {
                case Catch(cd, catchBody) =>
                    Emit.setPC(dispatchExceptionV)

                    // Connect the dispatcher to that catch
                    Emit.statementCont(CFG.Assume(CFG.Instanceof(ex, cd), EQUALS, CFG.PHPTrue), beginCatchV)
                    Emit.statementCont(CFG.Assume(CFG.Instanceof(ex, cd), NOTEQUALS, CFG.PHPTrue), nextCatchV)

                    Emit.setPC(beginCatchV)
                    stmt(catchBody)
                    Emit.goto(cont)

            }

            dispatchers = dispatchers.tail
            */
        case AST.Switch(input, cases) =>
            val beginSwitchV = Emit.getPC
            var curCaseV = cfg.newVertex
            var nextCaseV = cfg.newVertex
            var nextCondV = cfg.newVertex
            var conds: List[(AST.Expression, Vertex)] = Nil;
            var default: Option[Vertex] = None;

            cfg.openGroup("switch", beginSwitchV)

            controlStack = (cont, cont) :: controlStack

            Emit.setPC(curCaseV);
            // First, we put the statements in place:
            for(c <- cases) c match {
                case (None, ts) =>
                    default = Some(Emit.getPC)

                    Emit.setPC(curCaseV)
                    stmt(ts, nextCaseV)
                    curCaseV = nextCaseV
                    nextCaseV = cfg.newVertex
                case (Some(v), AST.Block(List())) =>
                    conds = conds ::: (v, Emit.getPC) :: Nil
                case (Some(v), AST.Block(tss)) =>
                    conds = conds ::: (v, Emit.getPC) :: Nil

                    Emit.setPC(curCaseV)
                    stmts(tss, nextCaseV)
                    curCaseV = nextCaseV
                    nextCaseV = cfg.newVertex
            }


            // Then, we link to conditions
            Emit.setPC(beginSwitchV)
            for(c <- conds) c match {
                case (ex, v) => 
                    condExpr(AST.Equal(input, ex), nextCondV, v)
                    Emit.setPC(nextCondV)
                    nextCondV = cfg.newVertex
            }

            default match {
                case Some(v) =>
                    Emit.statementCont(CFG.Skip, v);
                case None =>
                    Emit.statementCont(CFG.Skip, cont);
            }

            Emit.setPC(curCaseV);
            Emit.statementCont(CFG.Skip, cont);

            controlStack = controlStack.tail

            cfg.closeGroup(cont)
        case AST.Continue(AST.PHPInteger(level)) =>
            if (level > controlStack.length) {
                Reporter.error("Continue level exceeding control structure deepness.", s);
            } else {
                Emit.statementCont(CFG.Skip.setPos(s), controlStack((level-1).toInt)._1)
            }
        case AST.Continue(_) =>
            Reporter.notice("Dynamic continue statement ignored", s);
        case AST.Break(AST.PHPInteger(level)) =>
            if (level > controlStack.length) {
                Reporter.error("Break level exceeding control structure deepness.", s);
            } else {
                Emit.statementCont(CFG.Skip.setPos(s), controlStack((level-1).toInt)._2)
            }
        case AST.Break(_) =>
            Reporter.notice("Dynamic break statement ignored", s);
        case AST.Static(vars) =>
            for (v <- vars) v match {
                case AST.InitVariable(AST.SimpleVariable(id), Some(ex)) =>
                    Emit.statementCont(exprStore(idFromId(id), ex), cont)
                    Emit.setPC(cont);
                case AST.InitVariable(AST.SimpleVariable(id), None) =>
                    Emit.statementCont(CFG.Assign(idFromId(id), CFG.PHPNull().setPos(id)).setPos(s), cont)
                    Emit.setPC(cont);
                case _ => // ignore
                    Emit.goto(cont)
            }
        case AST.Global(vars) =>
            var nextGlobal = cfg.newVertex
            for (v <- vars) {
                v match {
                    case AST.SimpleVariable(id) =>
                        Emit.statementCont(CFG.Assign(idFromId(id), CFG.ArrayEntry(CFG.Identifier(scope.lookupVariable("GLOBALS").get).setPos(id), CFG.PHPString(id.value).setPos(id)).setPos(id)).setPos(s), nextGlobal)
                        Emit.setPC(nextGlobal)
                        nextGlobal = cfg.newVertex
                    case v =>
                        if (Main.verbosity >= 2) {
                            Reporter.notice("Non-trivial global statement ignored", v)
                        }
                }
            }
            Emit.goto(cont)
        case AST.Echo(exs) =>
            var nextEcho = cfg.newVertex
            var lastValidNext = nextEcho
            for (e <- exs) {
                Emit.statementCont(CFG.Print(expr(e)).setPos(s), nextEcho)
                Emit.setPC(nextEcho)
                lastValidNext = nextEcho
                nextEcho = cfg.newVertex
            }

            Emit.setPC(lastValidNext)
            Emit.goto(cont)
        case AST.Html(content) =>
            Emit.statementCont(CFG.Print(CFG.PHPString(content).setPos(s)).setPos(s), cont)
        case AST.Void() =>
            Emit.goto(cont)
        case AST.Unset(vars) =>
            var nextUnset = cfg.newVertex
            var lastValidNext = nextUnset
            for (v <- vars) {
                Emit.statementCont(CFG.Unset(varFromVar(v)).setPos(s), nextUnset)
                Emit.setPC(nextUnset)
                lastValidNext = nextUnset
                nextUnset = cfg.newVertex
            }

            Emit.setPC(lastValidNext)
            Emit.goto(cont)
        case AST.Return(expr) =>
            Emit.statementCont(exprStore(retval, expr), cfg.exit)
        case AST.Assign(AST.SimpleVariable(id), value, byref) =>
            Emit.statementCont(exprStore(idFromId(id), value), cont)
        case AST.Foreach(ex, as, _, optkey, _, body) =>
            val v = FreshVariable("val").setPos(ex)
            val condV = cfg.newVertex
            val assignCurV = cfg.newVertex
            val assignKeyV = cfg.newVertex
            val bodyV = cfg.newVertex
            val nextV = cfg.newVertex
            Emit.statementCont(exprStore(v, ex), condV)

            Emit.setPC(condV)
            Emit.statementCont(CFG.Assume(CFG.ArrayCurIsValid(v).setPos(s), CFG.EQUALS, CFG.PHPTrue().setPos(s)).setPos(s), assignCurV)
            Emit.statementCont(CFG.Assume(CFG.ArrayCurIsValid(v).setPos(s), CFG.NOTEQUALS, CFG.PHPTrue().setPos(s)).setPos(s), cont)

            Emit.setPC(assignCurV)
            Emit.statementCont(CFG.Assign(varFromVar(as), CFG.ArrayCurElement(v).setPos(s)).setPos(as), assignKeyV)

            Emit.setPC(assignKeyV)
            optkey match {
                case Some(k) =>
                    Emit.statementCont(CFG.Assign(varFromVar(k).setPos(k), CFG.ArrayCurKey(v).setPos(s)).setPos(k), bodyV)
                case None =>
                    Emit.goto(bodyV)
            }

            Emit.setPC(bodyV)

            controlStack = (nextV, cont) :: controlStack
            stmt(body, nextV)
            controlStack = controlStack.tail

            Emit.setPC(nextV)
            Emit.statementCont(CFG.Assign(v, CFG.ArrayNext(v).setPos(s)).setPos(s), condV)
        case _: AST.FunctionDecl | _: AST.ClassDecl | _: AST.InterfaceDecl => 
            /* ignore */
            Emit.goto(cont);
        case e: AST.Exit =>
            Emit.goto(cfg.exit)
            Emit.setPC(cfg.newVertex)
        case e: AST.Expression =>
            expr(e) match {
                case csv: CFG.Variable =>
                    Emit.goto(cont)
                case e =>
                    Emit.statementCont(e, cont);
            }
        case _ =>
            Reporter.notice("Not yet implemented (AST->CFG.): "+s, s);
      }
      Emit.setPC(cont)
    }

    /** Removes useless Skip edges by short-circuiting them. */
    def fewerSkips = {
      for (v <- cfg.V) {
        if ((v != cfg.entry) &&
              (v != cfg.exit) &&
              (v.out.size == 1)) {
          for (eOut <- v.out) {
            if (eOut.lab == CFG.Skip) {
              for (eIn <- v.in) {
                // remove old edge
                cfg -= (eIn.v1, eIn.lab, eIn.v2)
                cfg -= (eOut.v1, eOut.lab, eOut.v2)
                // insert new edge with label of incoming one
                cfg += (eIn.v1, eIn.lab, eOut.v2)
              }

              if (v.in.size == 0) {
                cfg -= (eOut.v1, eOut.lab, eOut.v2);
              }
            }
          }
        }
      }
    }

    Emit.setPC(cfg.entry)
    val codeEntry = cfg.newVertex
    Emit.statementBetween(cfg.entry, CFG.Assign(retval, CFG.PHPNull()) , codeEntry)
    Emit.setPC(codeEntry)
    stmts(statements, cfg.exit)
    Emit.setPC(cfg.exit)

    for ((l, vs) <- forwardGotos) {
        for ((v, p) <- vs) {
            Reporter.notice("Goto referencing an undefined label "+l, p)
        }
    }

    fewerSkips
    cfg
    }
}