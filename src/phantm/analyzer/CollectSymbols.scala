package phantm.analyzer

import phantm.Reporter
import phantm.Positional
import phantm.parser.Trees._
import phantm.analyzer.Symbols._
import phantm.analyzer.Types._

import scala.collection.mutable.HashSet
import scala.collection.mutable.HashMap

case class Context(varScope: Scope, cl: Option[ClassSymbol], iface: Option[IfaceSymbol]);

case class CollectSymbols(node: Tree) extends ASTTraversal[Context](node, Context(GlobalSymbols, None, None)) {
    var classCycleDetectionSet = new HashSet[ClassDecl]
    var classesToPass = List[ClassDecl]()
    var classList: List[(ClassSymbol, ClassDecl)] = Nil

    /**
     * Visit classes and add them to a waiting list, so they can be processed in right order
     */
    def visitClasses(node: Tree, ctx: Context): (Context, Boolean) = {
        node match {
            case cl @ ClassDecl(name, flags, parent, interfaces, methods, static_props, props, consts) =>
                classesToPass = classesToPass ::: List(cl)
            case id @ InterfaceDecl(name, parents, methods, consts) =>
                // TODO: actually safely register interfaces
                GlobalSymbols.registerIface(new IfaceSymbol(name.value, Nil))

            case _ =>
        }
        (ctx, true)
    }

    def firstClassPass : Unit = classesToPass match {
      case Nil =>
      case cd :: cds2 =>
          GlobalSymbols.lookupClass(cd.name.value) match {
            case None =>
              firstClassPass0(cd)
            case Some(x) =>
              Reporter.notice("Class " + x.name + " already declared (previously declared at "+x.getPos+")", cd)
          }
          classesToPass = cds2
          firstClassPass

    }

    def firstClassPass0(cd: ClassDecl): Unit = {
      if (classCycleDetectionSet.contains(cd)) { 
        Reporter.error("Classes " + classCycleDetectionSet.map(x => x.name.value).mkString(" -> ") + " form an inheritance cycle", cd)
        return;
      }

      classCycleDetectionSet += cd

      val p: Option[ClassSymbol] = cd.parent match {
        case Some(x) => GlobalSymbols.lookupClass(x.name.value) match {
          case None => {
            var foundParent = false
            for (c <- classesToPass if c.name.value.equals(x.name.value) && !foundParent) {
              firstClassPass0(c)
              foundParent = true
            }
              
            GlobalSymbols.lookupClass(x.name.value) match {
              case None => Reporter.error("Class " + cd.name.value + " extends non-existent class " + x.name.value, x); None
              case x => x
            }
          }
          case Some(pcs) => Some(pcs)
        }
        case None => None
      }
      val cs = new ClassSymbol(cd.name.value, p, Nil).setPos(cd);
      GlobalSymbols.registerClass(cs)

      classList = classList ::: List((cs,cd))
      classCycleDetectionSet -= cd
    }


    def secondClassPass(cd: ClassDecl, cs: ClassSymbol): Unit = {
        for (m <- cd.methods) {
            val ms = new MethodSymbol(cs, m.name.value, getVisibility(m.flags)).setPos(m)
            cs.registerMethod(ms)
            ms.registerPredefVariables
            for (a <- m.args) {
                var t = typeHintToType(a.hint)

                if (a.default == Some(PHPNull)) {
                    /*
                     * PHP Hack: if you pass null as default value, then null
                     * is also accepted as type
                     */
                     t = TUnion(t, TNull)
                }

                val as = new ArgumentSymbol(a.v.name.value, a.byref, a.default != None).setPos(a.v)
                as.typ = t
                ms.registerArgument(as);
            }

            val t = if (m.comment != None) {
                val (args, ret) = SourceAnnotations.Parser.getFunctionTypes(m.comment.get)

                val ftargs = for ((n, as) <- ms.argList) yield {
                    if (args contains n) {
                        (args(n), as.optional)
                    } else {
                        (TAny, as.optional)
                    }
                }

                TFunction(ftargs, ret)
            } else {
                TFunction(Nil, TAny)
            }


            val ftargs = for (((n, a), i) <- ms.argList.zipWithIndex) yield {
                if (t.args.size <= i) {
                    (a.typ, a.optional)
                } else {
                    (checkTypeHint(t.args(i)._1, a.typ, a), a.optional)
                }
            }
            ms.registerFType(TFunction(ftargs, t.ret))
        }

        for (p <- cd.props) {
            val th = Evaluator.typeFromExpr(p.default)

            val ps = new PropertySymbol(cs, p.v.value, getVisibility(p.flags)).setPos(p)

            val t = if (p.comment != None) {
                SourceAnnotations.Parser.getVarType(p.comment.get).getOrElse(th)
            } else {
                th
            }

            ps.typ = checkTypeHint(t, th, p)
            cs.registerProperty(ps)
        }

        for (p <- cd.static_props) {
            val th = Evaluator.typeFromExpr(p.default)

            val ps = new PropertySymbol(cs, p.v.value, getVisibility(p.flags)).setPos(p)

            val t = if (p.comment != None) {
                SourceAnnotations.Parser.getVarType(p.comment.get).getOrElse(th)
            } else {
                th
            }

            ps.typ = checkTypeHint(t, th, p)


            cs.registerStaticProperty(ps)
        }

        for (c <- cd.consts) {
            val ccs = c.value match {
                case sc: Scalar => 
                    new ClassConstantSymbol(cs, c.v.value, Some(sc)).setPos(c)
                case _ =>
                    new ClassConstantSymbol(cs, c.v.value, None).setPos(c)
            }

            val th = Evaluator.typeFromExpr(c.value)
            val t = if (c.comment != None) {
                SourceAnnotations.Parser.getConstType(c.comment.get).getOrElse(th)
            } else {
                th
            }

            ccs.typ = checkTypeHint(t, th, c)

            cs.registerConstant(ccs)
        }
    }

    def checkTypeHint(annoType: Type, hintType: Type, pos: Positional): Type = {
        import phantm.controlflow.TypeFlow._
        val res = TypeLattice.meet(BaseTypeEnvironment, BaseTypeEnvironment, annoType, hintType)._2

        if (res == TBottom) {
            Reporter.notice("Annotation incompatible with type hint or default value", pos)
            annoType
        } else {
            res
        }
    }

    /**
     * Visit the nodes and aggregate information inside the context to provide
     * hints about obvious errors directly from the AST
     */
    def visit(node: Tree, ctx: Context): (Context, Boolean) = {
        var newCtx = ctx;
        var continue = true;

        node match {
            case fd @ FunctionDecl(name, args, retref, body) =>
                val fs = new FunctionSymbol(name.value).setPos(name).setUserland
                for(a <- args) {
                    var t = typeHintToType(a.hint)

                    if (a.default == Some(PHPNull)) {
                        /*
                         * PHP Hack: if you pass null as default value, then null
                         * is also accepted as type
                         */
                         t = t union TNull
                    }

                    val as = new ArgumentSymbol(a.v.name.value, a.byref, a.default != None).setPos(a.v)
                    as.typ = t

                    fs.registerArgument(as);
                }

                val t = if (fd.comment != None) {
                    val (args, ret) = SourceAnnotations.Parser.getFunctionTypes(fd.comment.get)

                    val ftargs = for ((n, as) <- fs.argList) yield {
                        if (args contains n) {
                            (args(n), as.optional)
                        } else {
                            (TAny, as.optional)
                        }
                    }

                    TFunction(ftargs, ret)
                } else {
                    // TODO: The prototypes should be checked
                    TFunction(Nil, TAny)
                }

                val ftargs = for (((n, a), i) <- fs.argList.zipWithIndex) yield {
                    if (t.args.size <= i) {
                        (a.typ, a.optional)
                    } else {
                        checkTypeHint(t.args(i)._1, Evaluator.typeFromExpr(args(i).default), a)
                        (checkTypeHint(t.args(i)._1, a.typ, a), a.optional)
                    }
                }
                fs.registerFType(TFunction(ftargs, t.ret))

                fs.registerPredefVariables
                GlobalSymbols.registerFunction(fs)
                name.setSymbol(fs)
                newCtx = Context(fs, None, None)

            case ClassDecl(name, flags, parent, interfaces, methods, static_props, props, consts) =>
                GlobalSymbols.lookupClass(name.value) match {
                    case Some(cs) =>
                        name.setSymbol(cs);
                        newCtx = Context(ctx.varScope, Some(cs), None)
                    case None => error("Woops ?!? Came across a phantom class");
                }

            case InterfaceDecl(name, parents, methods, consts) =>
                GlobalSymbols.lookupIface(name.value) match {
                    case Some(iface) =>
                        newCtx = Context(ctx.varScope, None, Some(iface))
                    case None => error("Woops ?!? Came across a phantom interface");
                }

            case MethodDecl(name, flags, args, retref, body) =>
                (ctx.cl, ctx.iface) match {
                    case (Some(cs), _) => cs.lookupMethod(name.value, Some(cs)) match {
                        case LookupResult(Some(ms: MethodSymbol), _, _) =>
                            name.setSymbol(ms)
                            newCtx = Context(ms, Some(cs), None)
                        case _ => error("Woops?! No such method declared yet??")
                    }
                    case (None, Some(iface)) =>
                        // nothing
                    case (None, None) =>
                        error("Woops?!? Got into a method without any class or interface in the context: (Method: "+name.value+", "+name.getPos+")")
                }
            case _: ArgumentDecl =>
                // Skip SimpleVariables contained inside arguments declarations
                continue = false;


            case SimpleVariable(id) =>
                ctx.varScope.lookupVariable(id.value) match {
                    case Some(vs) =>
                        id.setSymbol(vs)
                    case None =>
                        val vs = new VariableSymbol(id.value).setPos(id)
                        id.setSymbol(vs);
                        ctx.varScope.registerVariable(vs)
                }

            case StaticClassRef(_, _, id) =>
                GlobalSymbols.lookupClass(id.value) match {
                    case Some(cs) =>
                        id.setSymbol(cs)
                    case None =>
                        ctx.cl match {
                            case Some(cs) =>
                                if (id.value == "self") {
                                    id.setSymbol(cs)
                                } else if (id.value == "parent") {
                                    cs.parent match {
                                        case Some(pcs) =>
                                            id.setSymbol(cs)
                                        case None =>
                                            Reporter.error("Class '"+cs.name+"' has no parent", id);
                                    }
                                }
                            case None =>
                                if (id.value == "self" || id.value == "parent") {
                                    Reporter.error(id.value+" cannot be used outside of a class definition", id);
                                }
                        }
                }

            case _ =>
        }

        (newCtx, continue)
    }

    def getVisibility(flags: List[MemberFlag]): MemberVisibility = flags match {
        case MFPublic :: xs => MVPublic
        case MFProtected :: xs => MVProtected
        case MFPrivate :: xs => MVPrivate
        case _ :: xs => getVisibility(xs)
        case Nil => MVPublic
    }

    def execute = {
        traverse(visitClasses)

        firstClassPass;
        for(c <- classList) {
            secondClassPass(c._2, c._1);
        }

        traverse(visit)

        GlobalSymbols.registerPredefVariables
    }

}
