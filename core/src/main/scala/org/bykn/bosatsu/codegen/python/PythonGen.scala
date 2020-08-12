package org.bykn.bosatsu.codegen.python

import org.bykn.bosatsu.{PackageName, Identifier, Matchless, Lit, RecursionKind}
import cats.Monad
import cats.data.NonEmptyList

import Identifier.Bindable
import Matchless._

import cats.implicits._

object PythonGen {
  // Structs are represented as tuples
  // Enums are represented as tuples with an additional first field holding
  // the variant

  sealed trait Code
  // Expressions or Code that has a final value
  sealed trait ValueLike extends Code
  sealed abstract class Expression extends ValueLike {
    def identOrParens: Expression =
      this match {
        case i: Code.Ident => i
        case p => Code.Parens(p)
      }
  }
  sealed abstract class Statement extends Code {
    import Code.Block

    def statements: NonEmptyList[Statement] =
      this match {
        case Block(ss) => ss
        case notBlock => NonEmptyList(notBlock, Nil)
      }

    def +:(stmt: Statement): Block =
      Block(stmt :: statements)
    def :+(stmt: Statement): Block =
      Block(statements :+ stmt)
  }

  object Code {
    // True, False, None, numbers
    case class Literal(asString: String) extends Expression
    case class PyString(content: String) extends Expression
    case class Ident(name: String) extends Expression
    // Binary operator used for +, -, and, == etc...
    case class Op(left: Expression, name: String, right: Expression) extends Expression
    case class Parens(expr: Expression) extends Expression
    case class SelectTuple(arg: Expression, position: Int) extends Expression
    case class MakeTuple(args: List[Expression]) extends Expression
    case class Lambda(args: List[Ident], result: Expression) extends Expression
    case class Apply(fn: Expression, args: List[Expression]) extends Expression
    case class DotSelect(ex: Expression, ident: Ident) extends Expression

    // this prepares an expression with a number of statements
    case class WithValue(statement: Statement, value: ValueLike) extends ValueLike {
      def +:(stmt: Statement): WithValue =
        WithValue(stmt +: statement, value)

      def :+(stmt: Statement): WithValue =
        WithValue(statement :+ stmt, value)
    }
    case class IfElse(conds: NonEmptyList[(Expression, ValueLike)], elseCond: ValueLike) extends ValueLike

    case class Block(stmts: NonEmptyList[Statement]) extends Statement
    case class IfStatement(conds: NonEmptyList[(Expression, Statement)], elseCond: Option[Statement]) extends Statement
    case class Def(name: Ident, args: List[Ident], body: Statement) extends Statement
    case class Return(expr: Expression) extends Statement
    case class Assign(variable: Ident, value: Expression) extends Statement
    case object Pass extends Statement
    case class While(cond: Expression, body: Statement) extends Statement
    case class Import(modname: String, alias: Option[Ident]) extends Statement

    def addAssign(variable: Ident, code: ValueLike): Statement =
      code match {
        case x: Expression =>
          Assign(variable, x)
        case WithValue(stmt, v) =>
          stmt +: addAssign(variable, v)
        case IfElse(conds, elseCond) =>
          IfStatement(
            conds.map { case (b, v) =>
              (b, addAssign(variable, b))
            },
            Some(addAssign(variable, elseCond))
          )
      }

    // boolean expressions can contain side effects
    // this runs the side effects but discards
    // and resulting value
    // we could assert the value, statically
    // that assertion should always be true
    def always(v: ValueLike): Statement =
      v match {
        case x: Expression => Pass
        case WithValue(stmt, v) =>
          stmt +: always(v)
        case IfElse(conds, elseCond) =>
          IfStatement(
            conds.map { case (b, v) =>
              (b, always(v))
            },
            Some(always(elseCond))
          )
      }

    def onLasts(cs: List[ValueLike])(fn: List[Expression] => ValueLike): Env[ValueLike] = {
      def loop(cs: List[ValueLike], setup: List[Statement], args: List[Expression]): Env[ValueLike] =
        cs match {
          case Nil => Monad[Env].pure {
            val res = fn(args.reverse)
            NonEmptyList.fromList(setup) match {
              case None => res
              case Some(nel) =>
                WithValue(Block(nel.reverse), res)
            }
          }
          case (e: Expression) :: t => loop(t, setup, e :: args)
          case (ifelse@IfElse(_, _)) :: tail =>
            // we allocate a result and assign
            // the result on each value
            Env.newAssignableVar.flatMap { v =>
              loop(tail, addAssign(v, ifelse) :: setup, v :: args)
            }
          case WithValue(decl, v) :: tail =>
            loop(v :: tail, decl :: setup, args)
        }

      loop(cs, Nil, Nil)
    }

    def ifElse(conds: NonEmptyList[(ValueLike, ValueLike)], elseV: ValueLike): Env[ValueLike] = {
      // for all the non-expression conditions, we need to defer evaluating them
      // until they are really needed
      conds match {
        case NonEmptyList((cx: Expression, t), Nil) =>
          Monad[Env].pure(IfElse(NonEmptyList((cx, t), Nil), elseV))
        case NonEmptyList((cx: Expression, t), rh :: rt) =>
          val head = (cx, t)
          ifElse(NonEmptyList(rh, rt), elseV).map {
            case IfElse(crest, er) =>
              // preserve IfElse chains
              IfElse(head :: crest, er)
            case nest =>
              IfElse(NonEmptyList(head, Nil), nest)
          }
        case NonEmptyList((cx, t), rest) =>
          for {
            // allocate a new unshadowable var
            cv <- Env.newAssignableVar
            res <- ifElse(NonEmptyList((cv, t), rest), elseV)
          } yield WithValue(addAssign(cv, t), res)
      }
    }

    def onLast(c: ValueLike)(fn: Expression => ValueLike): Env[ValueLike] =
      onLasts(c :: Nil) {
        case x :: Nil => fn(x)
        case other =>
          throw new IllegalStateException(s"expected list to have size 1: $other")
      }

    def andCode(c1: ValueLike, c2: ValueLike): Env[ValueLike] =
      onLasts(c1 :: c2 :: Nil) {
        case e1 :: e2 :: Nil =>
          Op(e1, Const.And, e2)
        case other =>
          throw new IllegalStateException(s"expected list to have size 2: $other")
      }

    def makeDef(defName: Code.Ident, arg: Code.Ident, v: ValueLike): Env[Code.Def] =
      Env.newAssignableVar.map { resName =>
        Code.Def(defName, arg :: Nil,
          Code.addAssign(resName, v) :+
            Code.Return(resName)
          )
      }

    def makeCurriedDef(name: Ident, args: NonEmptyList[Ident], body: ValueLike): Env[Statement] =
      args match {
        case NonEmptyList(a, Nil) =>
          //  base case
          makeDef(name, a, body)
        case NonEmptyList(a, h :: t) =>
          for {
            newName <- Env.newAssignableVar
            fn <- makeCurriedDef(newName, NonEmptyList(h, t), body)
          } yield Code.Def(name, a :: Nil, fn :+ Code.Return(newName))
      }


    def replaceTailCallWithAssign(name: Ident, args: NonEmptyList[Ident], body: ValueLike, cont: Ident): Env[ValueLike] = {
      val initBody = body
      def loop(body: ValueLike): Env[ValueLike] =
        body match {
          case Apply(x, as) if x == name && as.length == args.length =>
            // do the replacement
            val vs = args.toList.zip(as).map { case (v, x) => Assign(v, x) }

            val all = vs.foldLeft(Assign(cont, Const.True): Statement)(_ +: _)
            // set all the values and return the empty tuple
            Monad[Env].pure(WithValue(all, MakeTuple(Nil)))
          case Parens(p) => loop(p).flatMap(onLast(_)(Parens(_)))
          case IfElse(ifCases, elseCase) =>
            // only the result types are in tail position, we don't need to recurse on conds
            val ifs = ifCases.traverse { case (cond, res) => loop(res).map((cond, _)) }
            (ifs, loop(elseCase)).mapN(IfElse(_, _))
          case WithValue(stmt, v) =>
            loop(v).map(WithValue(stmt, _))
          // the rest cannot have a call in the tail position
          case DotSelect(_, _) | Apply(_, _) | Op(_, _, _) | Lambda(_, _) | MakeTuple(_) | SelectTuple(_, _) | Ident(_) | Literal(_) | PyString(_) => Monad[Env].pure(body)
        }

      loop(initBody)
    }

    // these are always recursive so we can use def to define them
    def buildLoop(name: Ident, args: NonEmptyList[Ident], body: ValueLike): Env[Statement] = {

      /*
       * bodyUpdate = body except App(foo, args) is replaced with
       * reseting the inputs, and setting cont to True and having
       * the value None
       *
       * def foo(a)(b)(c):
       *   cont = True
       *   res = None
       *   while cont:
       *     cont = False
       *     res = bodyUpdate
       *   return res
       */
      for {
        cont <- Env.newAssignableVar
        ac = Assign(cont, Const.True)
        res <- Env.newAssignableVar
        ar = Assign(res, Const.None)
        body1 <- replaceTailCallWithAssign(name, args, body, cont)
        setRes = addAssign(res, body1)
        loop = While(cont, Assign(cont, Const.False) +: setRes)
        newBody = WithValue(ac +: ar +: loop, res)
        curried <- makeCurriedDef(name, args, newBody)
      } yield curried
    }

    def litToExpr(lit: Lit): Expression =
      lit match {
        case Lit.Str(s) => Code.PyString(s)
        case Lit.Integer(bi) => Code.Literal(bi.toString)
      }

    object Const {
      val True = Literal("True")
      val False = Literal("False")
      val None = Literal("None")
      val Zero = Literal("0")
      val One = Literal("1")
      val Minus = "-"
      val Plus = "+"
      val And = "and"
      val Eq = "=="
      val Gt = ">"
    }

  }

  sealed abstract class Env[+A]
  object Env {
    implicit def envMonad: Monad[Env] = ???

    def render(env: Env[List[Statement]]): String = ???

    // allocate a unique identifier for b
    def bind(b: Bindable): Env[Code.Ident] = ???
    def bindArg(b: Bindable): Env[Code.Ident] = ???
    // get the mapping for a name in scope
    def deref(b: Bindable): Env[Code.Ident] = ???
    // release the current scope for b
    def unbind(b: Bindable): Env[Unit] = ???
    def unbindArg(b: Bindable): Env[Unit] = ???

    def nameForAnon(long: Long): Env[Code.Ident] = ???
    def newAssignableVar: Env[Code.Ident] = ???

    def importPackage(pack: PackageName): Env[Code.Ident] = ???
    // top level names are imported across files so they have
    // to be consistently transformed
    def topLevelName(n: Bindable): Env[Code.Ident] = ???
  }

  private[this] val python2Name = "[_A-Za-z][_0-9A-Za-z]*".r.pattern
  private[this] val base62Items = (('0' to '9') ++ ('A' to 'Z') ++ ('a' to 'z')).toSet

  private def toBase62(c: Char): String =

    if (base62Items(c)) c.toString
    else {
      def toChar(i0: Int): Char =
        if (i0 < 0) sys.error(s"invalid in: $i0")
        else if (i0 < 10) (i0 + '0'.toInt).toChar
        else if (i0 < 36) (i0 - 10 + 'A'.toInt).toChar
        else if (i0 < 62) (i0 - 36 + 'a'.toInt).toChar
        else sys.error(s"invalid int: $i0")

      def toString(i: Int): String = {
        if (i < 62) toChar(i).toString
        else {
          val i0 = i % 62
          val i1 = i / 62
          toString(i1) + toChar(i0)
        }
      }

      "_" + toString(c.toInt) + "_"
    }

  private def unBase62(str: String, offset: Int, bldr: java.lang.StringBuilder): Int = {
    var idx = offset
    var num = 0

    while(idx < str.length) {
      val c = str.charAt(idx)
      idx += 1
      if (c == '_') {
        // done
        val numC = num.toChar
        bldr.append(numC)
        return (idx - offset)
      }
      else {
        val base =
          if (c <= '9') '0'.toInt
          else if (c <= 'Z') ('A'.toInt - 10)
          else ('a'.toInt - 36)

        num = num * 62 + c.toInt - base
      }
    }
    return -1
  }

  // we escape by prefixing by three underscores, ___
  // then we escape _ by __ and any character outside the allowed
  // range by _base 62_
  def escape(n: Bindable): Code.Ident = {
    val str = n.sourceCodeRepr
    if (!str.startsWith("___") && python2Name.matcher(str).matches) Code.Ident(str)
    else {
      // we need to escape
      Code.Ident("___" + str.map(toBase62).mkString)
    }
  }
  def unescape(ident: Code.Ident): Option[Bindable] = {
    val str = ident.name
    val decode =
      if (str.startsWith("___")) {
        val bldr = new java.lang.StringBuilder()
        var idx = 3
        while (idx < str.length) {
          val c = str.charAt(idx)
          idx += 1
          if (c == '_') {
            val res = unBase62(str, idx, bldr)
            if (res < 1) return None
            else {
              idx += res
            }
          }
          else {
            bldr.append(c)
          }
        }

        bldr.toString()
      }
      else {
        str
      }

    Identifier.optionParse(Identifier.bindableParser, decode)
  }

  def packageToFile(pn: PackageName): List[String] = ???

  def apply(packName: PackageName, name: Bindable, me: Expr): Env[Statement] = {
    val ops = new Impl.Ops(packName)
    for {
      ve <- ops.loop(me)
      nm <- Env.topLevelName(name)
      stmt <- ops.topLet(nm, me, ve)
    } yield stmt
  }

  private object Impl {
    class Ops(packName: PackageName) {
      /*
       * enums with no fields are integers
       * enums and structs are tuples
       * enums first parameter is their index
       * nats are just integers
       */
      def makeCons(ce: ConsExpr, args: List[ValueLike]): Env[ValueLike] = {
        // invariant: args.size == arity
        def applyAll(args: List[ValueLike]): Env[ValueLike] =
          ce match {
            case MakeEnum(variant, arity) =>
              if (arity == 0) Monad[Env].pure(Code.Literal(variant.toString))
              else {
                // we make a tuple with the variant in the first position
                val vExpr = Code.Literal(variant.toString)
                Code.onLasts(vExpr :: args)(Code.MakeTuple(_))
              }
            case MakeStruct(arity) =>
                if (arity == 0) Monad[Env].pure(Code.MakeTuple(Nil))
                else if (arity == 1) Monad[Env].pure(args.head)
                else Code.onLasts(args)(Code.MakeTuple(_))
            case ZeroNat =>
              Monad[Env].pure(Code.Const.Zero)
            case SuccNat =>
              Code.onLast(args.head)(Code.Op(_, Code.Const.Plus, Code.Const.One))
          }

        val sz = args.size
        def makeLam(cnt: Int, args: List[ValueLike]): Env[ValueLike] =
          if (cnt == 0) applyAll(args)
          else if (cnt < 0) {
            // too many args, this shouldn't typecheck
            throw new IllegalStateException(s"invalid arity $sz for $ce")
          }
          else {
            // add an arg to the right
            for {
              v <- Env.newAssignableVar
              body <- makeLam(cnt - 1, args :+ v)
              res <- Code.onLast(body)(Code.Lambda(v :: Nil, _))
            } yield res
          }

        makeLam(ce.arity - sz, args)
      }

      def boolExpr(ix: BoolExpr): Env[ValueLike] =
        ix match {
          case EqualsLit(expr, lit) =>
            val literal = Code.litToExpr(lit)
            loop(expr).flatMap(Code.onLast(_) { ex => Code.Op(ex, Code.Const.Eq, literal) })
          case EqualsNat(nat, zeroOrSucc) =>
            val natF = loop(nat)

            if (zeroOrSucc.isZero)
              natF.flatMap(Code.onLast(_) { x =>
                Code.Op(x, Code.Const.Eq, Code.Const.Zero)
              })
            else
              natF.flatMap(Code.onLast(_) { x =>
                Code.Op(x, Code.Const.Gt, Code.Const.Zero)
              })

          case TrueConst => Monad[Env].pure(Code.Const.True)
          case And(ix1, ix2) =>
            (boolExpr(ix1), boolExpr(ix2))
              .mapN(Code.andCode(_, _))
              .flatten
          case CheckVariant(enumV, idx, size) =>
            loop(enumV).flatMap { tup =>
              Code.onLast(tup) { t =>
                val idxExpr = Code.Literal(idx.toString)
                if (size == 0) {
                  // this is represented as an integer
                  Code.Op(t, Code.Const.Eq, idxExpr)
                }
                else
                  Code.Op(Code.SelectTuple(t, 0), Code.Const.Eq, idxExpr)
              }
            }
          case SetMut(LocalAnonMut(mut), expr) =>
            (Env.nameForAnon(mut), loop(expr))
              .mapN { (ident, result) =>
                Code.onLast(result) { resx =>
                  val a = Code.Assign(ident, resx)
                  Code.WithValue(a, Code.Const.True)
                }
              }
              .flatten
          case MatchString(str, pat, binds) => ???
          case SearchList(LocalAnonMut(mutV), init, check, optLeft) => ???
        }

      def topLet(name: Code.Ident, expr: Expr, v: ValueLike): Env[Statement] = {

        /*
         * def anonF():
         *   code
         *
         * name = anonF()
         */
        lazy val worstCase: Env[Statement] =
          (Env.newAssignableVar, Env.newAssignableVar).mapN { (defName, resName) =>
            val newDef = Code.Def(defName, Nil,
              Code.addAssign(resName, v) :+
                Code.Return(resName)
              )

            newDef :+ Code.Assign(name, Code.Apply(defName, Nil))
          }

        expr match {
          case l@LoopFn(_, nm, h, t, b) =>
            Env.deref(nm)
              .flatMap { nm1 =>
                if (nm1 == name) {
                  val args = NonEmptyList(h, t)
                  (args.traverse(Env.bindArg), loop(b))
                    .mapN(Code.buildLoop(nm1, _, _))
                    .flatMap { res =>
                      res <* args.traverse_(Env.unbindArg)
                    }
                }
                else {
                  // we need to reassign the def to name
                  worstCase
                }
              }
          case Lambda(caps, arg, body) =>
            // this isn't recursive, or it would be in a Let
            (Env.bindArg(arg), loop(body))
              .mapN(Code.makeDef(name, _, _))
              .flatMap { d =>
                d <* Env.unbindArg(arg)
              }
          case Let(Right((n, RecursionKind.Recursive)), Lambda(_, arg, body), Local(n2)) if n == n2 =>
            Env.deref(n)
              .flatMap { ni =>
                if (ni == name) {
                  // this is a recursive value in scope for itself
                  (Env.bindArg(arg), loop(body))
                    .mapN(Code.makeDef(ni, _, _))
                    .flatMap(_ <* Env.unbindArg(arg))
                }
                else {
                  worstCase
                }
              }

          case _ => worstCase
        }
      }

      def loop(expr: Expr): Env[ValueLike] =
        expr match {
          case Lambda(_, arg, res) =>
            // python closures work the same so we don't
            // need to worry about what we capture
            (Env.bindArg(arg), loop(expr)).mapN { (arg, res) =>
              res match {
                case x: Expression =>
                  Monad[Env].pure(Code.Lambda(arg :: Nil, x))
                case v =>
                  for {
                    defName <- Env.newAssignableVar
                    defn <- Code.makeDef(defName, arg, v)
                  } yield Code.WithValue(defn, defName)
              }
            }
            .flatMap(_ <* Env.unbindArg(arg))
          case LoopFn(_, thisName, argshead, argstail, body) =>
            // closures capture the same in python, we can ignore captures
            val allArgs = NonEmptyList(argshead, argstail)
            (Env.bind(thisName), allArgs.traverse(Env.bindArg), loop(body))
              .mapN { (n, args, body) => Code.buildLoop(n, args, body).map(Code.WithValue(_, n)) }
              .flatMap(_ <* allArgs.traverse_(Env.unbindArg))
          case Global(p, n) =>
            if (p == packName) {
              // This is just a name in the local package
              Env.topLevelName(n)
            }
            else {
              (Env.importPackage(p), Env.topLevelName(n)).mapN(Code.DotSelect(_, _))
            }
          case Local(b) => Env.deref(b)
          case LocalAnon(a) => Env.nameForAnon(a)
          case LocalAnonMut(m) => Env.nameForAnon(m)
          case App(cons: ConsExpr, args) =>
            args.traverse(loop).flatMap { pxs => makeCons(cons, pxs.toList) }
          case App(expr, args) =>
            (loop(expr), args.traverse(loop))
              .mapN { (fn, args) =>
                Code.onLasts(fn :: args.toList) {
                  case fn :: ah :: atail =>
                    // all functions are curried, a future
                    // optimization would improve that
                    atail.foldLeft(Code.Apply(fn.identOrParens, ah :: Nil)) { (left, arg) =>
                      Code.Apply(Code.Parens(left), arg :: Nil)
                    }
                  case other => throw new IllegalStateException(s"got $other, expected to match $expr")
                }
              }
              .flatten
          case Let(localOrBind, value, in) =>
            val inF = loop(in)

            localOrBind match {
              case Right((b, rec)) =>
                if (rec.isRecursive) {
                  // value b is in scope first
                  for {
                    bi <- Env.bind(b)
                    ve <- loop(value)
                    tl <- topLet(bi, value, ve)
                    ine <- inF
                    wv = Code.WithValue(tl, ine)
                    _ <- Env.unbind(b)
                  } yield wv
                }
                else {
                  // value b is in scope after ve
                  for {
                    ve <- loop(value)
                    bi <- Env.bind(b)
                    tl <- topLet(bi, value, ve)
                    ine <- inF
                    wv = Code.WithValue(tl, ine)
                    _ <- Env.unbind(b)
                  } yield wv
                }
              case Left(LocalAnon(l)) =>
                // anonymous names never shadow
                (Env.nameForAnon(l), loop(value))
                  .mapN { (bi, vE) =>
                    (topLet(bi, value, vE), inF)
                      .mapN(Code.WithValue(_, _))
                  }
                  .flatten
            }

          case LetMut(LocalAnonMut(_), in) =>
            // we could delete this name, but
            // there is no need to
            loop(in)
          case Literal(lit) => Monad[Env].pure(Code.litToExpr(lit))
          case If(cond, thenExpr, elseExpr) =>
            def combine(expr: Expr): (List[(BoolExpr, Expr)], Expr) =
              expr match {
                case If(c1, t1, e1) =>
                  val (ifs, e2) = combine(e1)
                  (ifs :+ ((c1, t1)), e1)
                case last => (Nil, last)
              }

            val (rest, last) = combine(elseExpr)
            val ifs = NonEmptyList((cond, thenExpr), rest)

            val ifsV = ifs.traverse { case (c, t) =>
              (boolExpr(c), loop(t)).tupled
            }

            (ifsV, loop(elseExpr))
              .mapN { (ifs, elseV) =>
                Code.ifElse(ifs, elseV)
              }
              .flatten

          case Always(cond, expr) =>
            (boolExpr(cond).map(Code.always), loop(expr))
              .mapN {
                case (Code.Pass, v) => v
                case (notPass, v) =>
                  Code.WithValue(notPass, v)
              }

          case GetEnumElement(expr, _, idx, sz) =>
            // nonempty enums are just structs with the first element being the variant
            // we could assert the v matches when debugging, but typechecking
            // should assure this
            loop(expr).flatMap { tup =>
              Code.onLast(tup) { t =>
                Code.SelectTuple(t, idx + 1)
              }
            }
          case GetStructElement(expr, idx, sz) =>
            val exprR = loop(expr)
            if (sz == 1) {
              // we don't bother to wrap single item structs
              exprR
            }
            else {
              // structs are just tuples
              exprR.flatMap { tup =>
                Code.onLast(tup) { t =>
                  Code.SelectTuple(t, idx)
                }
              }
            }
          case PrevNat(expr) =>
            // Nats are just integers
            loop(expr).flatMap { nat =>
              Code.onLast(nat)(Code.Op(_, Code.Const.Minus, Code.Const.One))
            }
          case cons: ConsExpr => makeCons(cons, Nil)
        }
    }
  }
}
