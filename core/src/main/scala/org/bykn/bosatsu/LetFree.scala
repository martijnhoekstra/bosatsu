package org.bykn.bosatsu

import cats.data.{NonEmptyList, State}
import cats.implicits._
import cats.Id
import rankn._

import Identifier.Constructor

sealed abstract class LetFreeExpression {
  /*
   * maxLambdaVar is to keep track of what is the largest de bruijn index
   * of lambda variables in the expression. This is useful because if this number
   * is positive then there are unbound variables and it should not be cached (unless
   * you want to be clever about cacheing values for when they are bound in an outer
   * scope). And when they are negative it implies there are eta reduction opportunities.
   * None essentially means -Infinity as either there are no linked expressions
   * or there are no lambda variables used in the linked expressions
   */
  def maxLambdaVar: Option[Int]

  def serialize: String = {
    def escapeString(unescaped: String) = StringUtil.escape('\'', unescaped)
    this match {
      case LetFreeExpression.App(fn, arg) => s"App(${fn.serialize},${arg.serialize})"
      case LetFreeExpression.ExternalVar(pack, defName, tpe) => s"ExternalVar('${escapeString(pack.asString)}','${escapeString(defName.asString)}', '${escapeString(TypeRef.fromTypes(None, tpe :: Nil).apply(tpe).toDoc.render(100))}')"
      case LetFreeExpression.Match(arg, branches) => {
        val serBranches = branches.toList.map {case (lfp, lfe) => s"${lfp.serialize},${lfe.serialize}"}.mkString(",")
        s"Match(${arg.serialize},$serBranches)"
      }
      case LetFreeExpression.LambdaVar(index) => s"LambdaVar($index)"
      case LetFreeExpression.Lambda(expr) => s"Lambda(${expr.serialize})"
      case LetFreeExpression.Struct(enum, args, _) => s"Struct($enum,${args.map(_.serialize).mkString(",")})"
      case LetFreeExpression.Literal(toLit) => toLit match {
        case Lit.Str(toStr) => s"Literal('${escapeString(toStr)}')"
        case Lit.Integer(bigInt) => s"Literal($bigInt)"
      }
      case LetFreeExpression.Recursion(lambda) => s"Recursion(${lambda.serialize})"
    }
  }

  def varSet: Set[Int] = this match {
    case LetFreeExpression.Lambda(expr) => expr.varSet.collect { case n if n > 0 => n - 1}
    case LetFreeExpression.App(fn, arg) => fn.varSet ++ arg.varSet
    case LetFreeExpression.ExternalVar(_, _, _) => Set()
    case LetFreeExpression.Match(arg, branches) => branches.map {
      branch => branch._2.varSet.map(_ - LetFreePattern.varCount(0, List(branch._1))).filter(_ >= 0)
    }.foldLeft(arg.varSet){ case (s1, s2) => s1 ++ s2 }
    case LetFreeExpression.Struct(enum, args, _) => args.foldLeft(Set[Int]()) { case (s, arg) => s ++ arg.varSet }
    case LetFreeExpression.Literal(_) => Set()
    case LetFreeExpression.Recursion(lambda) => lambda.varSet.collect { case n if n > 0 => n - 1}
    case LetFreeExpression.LambdaVar(name) => Set(name)
  }
}

object LetFreeExpression {
  case class App(fn: LetFreeExpression, arg: LetFreeExpression)
  extends LetFreeExpression {
    def maxLambdaVar = (fn.maxLambdaVar.toList ++ arg.maxLambdaVar.toList)
      .reduceLeftOption(Math.max)
  }
  case class ExternalVar(pack: PackageName, defName: Identifier, tpe: rankn.Type)
  extends LetFreeExpression {
    def maxLambdaVar = None
  }
  case class Match(arg: LetFreeExpression,
    branches: NonEmptyList[(LetFreePattern, LetFreeExpression)])
  extends LetFreeExpression {
    def maxLambdaVar =
      (arg.maxLambdaVar.toList ++ branches.toList.flatMap(_._2.maxLambdaVar))
        .reduceLeftOption(Math.max)
  }
  case class LambdaVar(index: Int) extends LetFreeExpression {
    def maxLambdaVar = Some(index)
  }
  /*
   * It is reasonable to ask how you can define a lambda without an identifier
   * for its argument in its expression. This is a benefit of de bruijn indexing.
   * When all lambdas have exactly one argument you identify the var by how many
   * lambdas out you have to travel.
   *
   * eg \x -> \y -> [y,x] would have normalization Lambda(Lambda(Apply(Apply(List, LambdaVar(1)), LambdaVar(0))))
   *
   * ref: https://en.wikipedia.org/wiki/De_Bruijn_index
   */
  case class Lambda(expr: LetFreeExpression) extends LetFreeExpression {
    def maxLambdaVar = expr.maxLambdaVar.map(_ - 1)
  }
  case class Struct(enum: Int, args: List[LetFreeExpression], dataFamily: DataFamily) extends LetFreeExpression {
    def maxLambdaVar = args.flatMap(_.maxLambdaVar).reduceLeftOption(Math.max)
  }
  case class Literal(lit: Lit) extends LetFreeExpression {
    def maxLambdaVar = None
  }
  case class Recursion(lambda: LetFreeExpression) extends LetFreeExpression {
    def maxLambdaVar = lambda.maxLambdaVar
  }

}

sealed abstract class LetFreePattern {
  def escapeString(unescaped: String) = StringUtil.escape('\'', unescaped)
  def serialize: String =
    this match {
      case LetFreePattern.WildCard => "WildCard"
      case LetFreePattern.Literal(toLit) => toLit match {
        case Lit.Str(toStr) => s"Literal('${escapeString(toStr)}')"
        case Lit.Integer(bigInt) => s"Literal($bigInt)"
      }
      case LetFreePattern.Var(name) => s"Var($name)"
      case LetFreePattern.Named(name, pat) => s"Named($name,${pat.serialize})"
      case LetFreePattern.ListPat(parts) => {
        val inside = parts.map {
          case Left(name) => s"Left(${name.map(_.toString).getOrElse("")})"
          case Right(pat) => s"Right(${pat.serialize})"
        }.mkString(",")
        s"ListPat($inside)"
      }
      case LetFreePattern.PositionalStruct(name, params, df) => s"PositionalStruct(${name.map(_.toString).getOrElse("")},${params.map(_.serialize).mkString(",")})"
      case LetFreePattern.Union(head, rest) => s"Union(${head.serialize},${rest.toList.map(_.serialize).mkString(",")})"
      case LetFreePattern.StrPat(parts) => {
        val inside = parts.map {
          case LetFreePattern.StrPart.WildStr => "WildStr"
          case LetFreePattern.StrPart.NamedStr(name) => s"NamedStr($name)"
          case LetFreePattern.StrPart.LitStr(toString) => s"LitStr($toString)"
        }.toList.mkString(",")
        s"StrPat($inside)"
      }
    }
}

object LetFreePattern {
  def varCount(floor: Int, patterns: List[LetFreePattern]): Int = patterns match {
    case head :: rest => head match {
      case LetFreePattern.WildCard => floor
      case LetFreePattern.Literal(_) => 0
      case LetFreePattern.Var(name) => floor.max(name + 1)
      case LetFreePattern.Named(name, pat) => varCount(name + 1, List(pat))
      case LetFreePattern.ListPat(parts) => {
        val result = parts.foldLeft((floor, List[LetFreePattern]())) {
          case ((fl, lst), Left(n)) => (fl.max(n.getOrElse(fl)), lst)
          case ((fl, lst), Right(pat)) => (fl, pat :: lst)
        }
        varCount(result._1, result._2)
      }
      case LetFreePattern.PositionalStruct(name, params, df) => varCount(name.getOrElse(floor).max(floor), params)
      case LetFreePattern.Union(uHead, _) => varCount(floor, List(uHead))
      case LetFreePattern.StrPat(parts) => parts.foldLeft(floor) {
        case (n, LetFreePattern.StrPart.NamedStr(name)) => n.max(name)
        case (n, _) => n 
      }
    }
    case _ => floor
  }

  case object WildCard extends LetFreePattern
  case class Literal(toLit: Lit) extends LetFreePattern
  case class Var(name: Int) extends LetFreePattern
  /**
   * Patterns like foo @ Some(_)
   * @ binds tighter than |, so use ( ) with groups you want to bind
   */
  case class Named(name: Int, pat: LetFreePattern) extends LetFreePattern
  case class ListPat(parts: List[Either[Option[Int], LetFreePattern]]) extends LetFreePattern
  case class PositionalStruct(name: Option[Int], params: List[LetFreePattern], dataFamily: DataFamily) extends LetFreePattern
  case class Union(head: LetFreePattern, rest: NonEmptyList[LetFreePattern]) extends LetFreePattern
  case class StrPat(parts: NonEmptyList[StrPart]) extends LetFreePattern

  sealed abstract class StrPart
  object StrPart {
    final case object WildStr extends StrPart
    final case class NamedStr(idx: Int) extends StrPart
    final case class LitStr(asString: String) extends StrPart
  }
}

object LetFreeConversion {
  case class ExpressionKeyTag[T](lfe: T, children: Set[T])
  type LetFreeExpressionTag = ExpressionKeyTag[LetFreeExpression]
  def LetFreeExpressionTag(lfe: LetFreeExpression, children: Set[LetFreeExpression]) = ExpressionKeyTag(lfe, children)
  type LetFreePM = PackageMap.Typed[(Declaration, LetFreeExpressionTag)]
  type LetFreePac = Package.Typed[(Declaration, LetFreeExpressionTag)]

  type PatternEnv[T] = Map[Int, T]

  sealed trait PatternMatch[+A]
  case class Matches[A](env: A) extends PatternMatch[A]
  case object NoMatch extends PatternMatch[Nothing]
  case object NotProvable extends PatternMatch[Nothing]

  def noop[T]: (T, PatternEnv[T]) => PatternMatch[PatternEnv[T]] = { (_, env) => Matches(env) }
  def neverMatch[T]: (T, PatternEnv[T]) => PatternMatch[Nothing] = { (_, _) => NoMatch }

  def structListAsList(lfe: LetFreeExpression): Option[List[LetFreeExpression]] = {
    lfe match {
      case LetFreeExpression.Struct(0, _, _) => Some(Nil)
      case LetFreeExpression.Struct(1, List(value, tail), _) => structListAsList(tail).map(value :: _)
      case _ => None
    }
  }

  def listAsStructList(lst: List[LetFreeExpression]): LetFreeExpression =
    lst.foldRight(LetFreeExpression.Struct(0, Nil, DataFamily.Enum)) { case (lfe, acc) => LetFreeExpression.Struct(1, List(lfe, acc), DataFamily.Enum) }

  case class LitValue(toAny: Any) {
    def equivToLit(lit: Lit) = lit match {
      case Lit.Integer(i) => i == toAny
      case Lit.Str(s) => s == toAny
    }
  }

  object LitValue {
    def fromLit(lit: Lit) = lit match {
      case Lit.Integer(i) => LitValue(i)
      case Lit.Str(s) => LitValue(s)
    }
  }

  implicit val neToLitValue: LetFreeExpression => Option[LitValue] = {
    case LetFreeExpression.Literal(lit) => Some(LitValue.fromLit(lit))
    case _ => None
  }
  implicit val neToStruct: (LetFreeExpression, DataFamily) => Option[(Int, List[LetFreeExpression])] = {
    case (LetFreeExpression.Struct(enum, args, _), df) => Some((enum, args))
    case _ => None
  }
  implicit val neToList: LetFreeExpression => Option[List[LetFreeExpression]] = structListAsList(_)
  implicit val neFromList: List[LetFreeExpression] => LetFreeExpression = listAsStructList(_)

  def maybeBind[T](pat: LetFreePattern)(implicit
    toLitValue: T => Option[LitValue],
    toStruct: (T, DataFamily) => Option[(Int, List[T])],
    toList: T => Option[List[T]],
    fromList: List[T] => T
    ): (T, PatternEnv[T]) => PatternMatch[PatternEnv[T]] =
    pat match {
      case LetFreePattern.WildCard => noop
      case LetFreePattern.Literal(lit) =>
        { (v, env) => toLitValue(v) match {
          case Some(lv) => if (lv.equivToLit(lit)) Matches(env) else NoMatch
          case _ => NotProvable
        }}
      case LetFreePattern.Var(n) =>
        { (v, env) => Matches(env + (n ->  v)) }
      case LetFreePattern.Named(n, p) =>
        val inner = maybeBind[T](p)

        { (v, env) =>
          inner(v, env) match {
            case Matches(env1) => Matches(env1 + (n -> v))
            case notMatch => notMatch
          }
        }
      case LetFreePattern.ListPat(items) =>
        items match {
          case Nil =>
            { (arg, acc) =>
              toStruct(arg, DataFamily.Enum) match {
                case Some((0, _)) => Matches(acc)
                case Some((1, _)) => NoMatch
                case _ => NotProvable
              }
            }
          case Right(ph) :: ptail =>
            // a right hand side pattern never matches the empty list
            val fnh = maybeBind[T](ph)
            val fnt = maybeBind[T](LetFreePattern.ListPat(ptail))

            { (arg, acc) =>
              toStruct(arg, DataFamily.Enum) match {
                case Some((1, List(argHead, structTail))) =>
                  fnh(argHead, acc) match {
                    case NoMatch => NoMatch
                    case NotProvable => fnt(structTail, acc) match {
                      case NoMatch => NoMatch
                      case _ => NotProvable
                    }
                    case Matches(acc1) => fnt(structTail, acc1)
                  }
                case Some(_) => NoMatch
                case _ => NotProvable
              }
            }
          case Left(splice) :: Nil =>
            // this is the common and easy case: a total match of the tail
            // we don't need to match on it being a list, because we have
            // already type checked
            splice match {
              case Some(ident) =>
                { (v, env) => Matches(env + (ident -> v)) }
              case None =>
                noop
            }
          case Left(splice) :: ptail =>
            // this is more costly, since we have to match a non infinite tail.
            // we reverse the tails, do the match, and take the rest into
            // the splice
            val revPat = LetFreePattern.ListPat(ptail.reverse)
            val fnMatchTail = maybeBind[T](revPat)
            val ptailSize = ptail.size

            { (arg, acc) =>
              // we only allow one splice, so we assume the rest of the patterns
              toList(arg) match {
                case None => NotProvable
                case Some(asList) =>
                  val (revArgTail, spliceVals) = asList.reverse.splitAt(ptailSize)
                  fnMatchTail(fromList(revArgTail), acc) match {
                    case m@Matches(acc1) => splice.map {nm =>
                      val rest = fromList(spliceVals.reverse)
                      Matches(acc1 + (nm -> rest))
                    }.getOrElse(m)
                    case notMatch => notMatch
                  }
              }
            }
        }
      case LetFreePattern.Union(h, t) =>
        // we can just loop expanding these out:
        def loop(ps: List[LetFreePattern]): (T, PatternEnv[T]) => PatternMatch[PatternEnv[T]] =
          ps match {
            case Nil => neverMatch
            case head :: tail =>
              val fnh = maybeBind[T](head)
              val fnt: (T, PatternEnv[T]) => PatternMatch[PatternEnv[T]] = loop(tail)
              val result: (T, PatternEnv[T]) => PatternMatch[PatternEnv[T]] = { case (arg, acc) =>
                fnh(arg, acc) match {
                  case NoMatch  => fnt(arg, acc)
                  case notNoMatch => notNoMatch
                }
              }
              result
          }
        loop(h :: t.toList)
      case LetFreePattern.PositionalStruct(maybeIdx, items, df) =>
        // The type in question is not the outer dt, but the type associated
        // with this current constructor
        val itemFns = items.map(maybeBind[T](_))

        def processArgs(as: List[T], acc: PatternEnv[T]): PatternMatch[PatternEnv[T]] = {
          // manually write out foldM hoping for performance improvements
          @annotation.tailrec
          def loop(vs: List[T], fns: List[(T, PatternEnv[T]) => PatternMatch[PatternEnv[T]]], env: (PatternEnv[T], PatternMatch[PatternEnv[T]])): PatternMatch[PatternEnv[T]] =
            vs match {
              case Nil => env._2
              case vh :: vt =>
                fns match {
                  case fh :: ft =>
                    (fh(vh, env._1), env._2) match {
                      case (_, NoMatch) => NoMatch
                      case (NoMatch, _) => NoMatch
                      case (Matches(env1), Matches(_)) => loop(vt, ft, (env1, Matches(env1)))
                      case (Matches(env1), NotProvable) => loop(vt, ft, (env1, NotProvable))
                      case (NotProvable, _) => loop(vt, ft, (env._1, NotProvable))
                    }
                  case Nil => env._2 // mismatch in size, shouldn't happen statically
                }
            }
          loop(as, itemFns, (acc, Matches(acc)))
        }

        maybeIdx match {
          case None =>
            // this is a struct, which means we expect it
            { (arg: T, acc: PatternEnv[T]) =>
              toStruct(arg, df) match {
                case Some((_, args)) =>
                  processArgs(args, acc)
                case _ =>
                  NotProvable
              }
            }

          case Some(idx) =>
            // we don't check if idx < 0, because if we compiled, it can't be
            val result = { (arg: T, acc: PatternEnv[T]) =>
              toStruct(arg, df) match {
                case Some((enumId, args)) =>
                  if (enumId == idx) processArgs(args, acc)
                  else NoMatch
                case _ =>
                  NotProvable
              }
            }
            result
        }
        case LetFreePattern.StrPat(parts) => {(v, env) => NotProvable}
  }

  def findMatch(m: LetFreeExpression.Match) =
    m.branches.collectFirst(Function.unlift( { case (pat, result) =>
      maybeBind[LetFreeExpression](pat).apply(m.arg, Map()) match {
        case Matches(env) => Some(Some((pat, env, result)))
        case NotProvable => Some(None)
        case NoMatch => None
      }
    })).get // Totallity of matches should ensure this will always find something unless something has gone terribly wrong

  def solveMatch(env: PatternEnv[LetFreeExpression], result: LetFreeExpression) =
    ((env.size - 1) to 0 by -1).map(env.get(_).get) // If this exceptions then somehow we didn't get enough names in the env
      .foldLeft(result) { case (lfe, arg) => LetFreeExpression.App(lfe, arg) }

  def normalOrderReduction(expr: LetFreeExpression): LetFreeExpression = {
    import LetFreeExpression._
    val res = headReduction(expr) match {
      case App(fn, arg) =>
        App(normalOrderReduction(fn), normalOrderReduction(arg))
      case extVar @ ExternalVar(_, _, _) => extVar
      // check for a match reduction opportunity (beta except for Match instead of lambda)
      case Match(arg, branches) =>
        Match(normalOrderReduction(arg), branches.map{ case (p, s) => (p, normalOrderReduction(s))})
      case lv @ LambdaVar(_)  => lv
      // check for eta reduction
      case Lambda(expr)       =>
        Lambda(normalOrderReduction(expr))
      case Struct(enum, args, df) => Struct(enum, args.map(normalOrderReduction(_)), df)
      case l @ Literal(_)     => l
      case Recursion(innerExpr) => Recursion(normalOrderReduction(innerExpr))
    }
    if (res != expr) {
      normalOrderReduction(res)
    } else {
      res
    }
  }

  @annotation.tailrec
  def headReduction(expr: LetFreeExpression): LetFreeExpression = {
    import LetFreeExpression._
    val nextExpr = expr match {
      // beta reduction
      case App(Lambda(nextExpr), arg) =>
        applyLambdaSubstituion(nextExpr, Some(arg), 0)
      // match reduction
      case m@Match(_, _) =>
        findMatch(m) match {
          case None => m
          case Some((pat, env, result)) =>
            solveMatch(env, result)
        }
      case Recursion(Lambda(innerExpr)) if(innerExpr.maxLambdaVar.map(_ < 0).getOrElse(true)) =>
        applyLambdaSubstituion(innerExpr, None, 0)
      // eta reduction
      case Lambda(App(innerExpr, LambdaVar(0))) if innerExpr.maxLambdaVar.map(_ < 0).getOrElse(true) =>
        applyLambdaSubstituion(innerExpr, Some(LambdaVar(0)), 0)
      case _ => expr
    }

    if (expr != nextExpr) {
      headReduction(nextExpr)
    } else {
      nextExpr
    }
  }

  private def applyLambdaSubstituion(expr: LetFreeExpression,
    subst: Option[LetFreeExpression],
    idx: Int): LetFreeExpression = {
      import LetFreeExpression._
      expr match {
        case App(fn, arg)                           =>
          App(applyLambdaSubstituion(fn, subst, idx),
            applyLambdaSubstituion(arg, subst, idx))
        case ext @ ExternalVar(_, _, _)                => ext
        case Match(arg, branches)                   =>
          Match(applyLambdaSubstituion(arg, subst, idx), branches.map {
            case (enum, expr) => (enum, applyLambdaSubstituion(expr, subst, idx))
          })
        case LambdaVar(varIndex) if varIndex == idx => subst.get
        case LambdaVar(varIndex) if varIndex > idx  => LambdaVar(varIndex - 1)
        case lv @ LambdaVar(_)                      => lv
        case Lambda(fn)                             => Lambda(applyLambdaSubstituion(fn, subst.map(incrementLambdaVars(_, 0)), idx + 1))
        case Struct(enum, args, df)                     =>
          Struct(enum, args.map(applyLambdaSubstituion(_, subst, idx)), df)
        case l @ Literal(_)                         => l
        case r @ Recursion(fn)                      => Recursion(applyLambdaSubstituion(fn, subst, idx))
      }
  }

  def incrementLambdaVars(expr: LetFreeExpression, lambdaDepth: Int): LetFreeExpression = {
    import LetFreeExpression._
    expr match {
      case App(fn, arg) =>
        App(incrementLambdaVars(fn, lambdaDepth),
          incrementLambdaVars(arg, lambdaDepth))
      case ext @ ExternalVar(_, _, _) => ext
      case Match(arg, branches) =>
        Match(incrementLambdaVars(arg, lambdaDepth), branches.map {
          case (enum, expr) => (enum, incrementLambdaVars(expr, lambdaDepth))
        })
      case LambdaVar(varIndex) if varIndex >= lambdaDepth => LambdaVar(varIndex + 1)
      case lv @ LambdaVar(_)                      => lv
      case Lambda(fn)                             => Lambda(incrementLambdaVars(fn, lambdaDepth + 1))
      case Struct(enum, args, df) =>
        Struct(enum, args.map(incrementLambdaVars(_, lambdaDepth)), df)
      case l @ Literal(_) => l
      case Recursion(fn) => Recursion(incrementLambdaVars(fn, lambdaDepth))
    }
  }
}

case class LetFreePackageMap(pm: PackageMap.Inferred) {
  import LetFreeConversion._
  import TypedExpr._

  val letFreePackageMap: LetFreePM = {
    val packs = pm.toMap.toList
    val normAll = packs.traverse { case (name, pack) =>
      letFreeConvertPackage(name, pack)
        .map((name, _))
    }
    PackageMap(normAll.run(Map()).value._2.toMap)
  }

  def hashKey[T](fn: LetFreeExpression => T): PackageMap.Typed[(Declaration, ExpressionKeyTag[T])] = {
    val lst = letFreePackageMap.toMap.toList
      .map { case (packName, pack) =>
        val newLets = pack.program.lets.map { case (letsName, recursive, expr) =>
          val newExpr = expr.traverse[Id, (Declaration, ExpressionKeyTag[T])] {
            case (d, lfeT) => (d, ExpressionKeyTag(fn(lfeT.lfe), lfeT.children.map(fn)))
          }
          (letsName, recursive, newExpr)
        }
        val newProgram = pack.program.copy(lets = newLets)
        val newPack = pack.copy(program = newProgram)
        (packName, newPack)
      }
    PackageMap(lst.toMap)
  }

  def letFreeConvertExpr(expr: TypedExpr[Declaration], env: Env, p: Package.Inferred):
    NormState[TypedExpr[(Declaration, LetFreeExpressionTag)]] = {
      expr match {
        case a@Annotation(_, _, _) => letFreeConvertAnnotation(a, env, p)
        case g@Generic(_, _, _) => letFreeConvertGeneric(g, env, p)
        case v@Local(_, _, _) => letFreeConvertLocal(v, env, p)
        case v@Global(_, _, _, _) => letFreeConvertGlobal(v, env, p)
        case al@AnnotatedLambda(_, _, _, _) => letFreeConvertAnnotatedLambda(al, env, p)
        case a@App(_, _, _, _) => letFreeConvertApp(a, env, p)
        case l@Let(_, _, _, _, _) => letFreeConvertLet(l, env, p)
        case l@Literal(_, _, _) => letFreeConvertLiteral(l, env, p)
        case m@Match(_, _, _) => letFreeConvertMatch(m, env, p)
      }
    }

  private def combineWithChildren(nt: LetFreeExpressionTag) = nt.children + nt.lfe

  def letFreeConvertAnnotation(a: Annotation[Declaration], env: Env, p: Package.Inferred):
    NormState[TypedExpr[(Declaration, LetFreeExpressionTag)]] =
      letFreeConvertExpr(a.term, env, p).map { term =>
        val lfeTag = term.tag._2
        val tag = (a.tag, lfeTag)
        a.copy(term=term, tag=tag)
      }

  def letFreeConvertGeneric(g: Generic[Declaration], env: Env, p: Package.Inferred):
    NormState[TypedExpr[(Declaration, LetFreeExpressionTag)]] =
      letFreeConvertExpr(g.in, env, p).map { in =>
        val lfeTag = in.tag._2
        val tag = (g.tag, lfeTag)
        g.copy(in=in, tag=tag)
      }

  def letFreeConvertLocal(v: Local[Declaration], env: Env, p: Package.Inferred):
    NormState[TypedExpr[(Declaration, LetFreeExpressionTag)]] =
      env._1.get(v.name) match {
        case None => norm(p, v.name, v.tag, env).map { lfe =>
          val lfeTag = getTag(lfe)._2
          v.copy(tag=(v.tag, lfeTag))
        }
        case Some(lfeTag) =>
          State.pure(v.copy(tag=(v.tag, lfeTag)))
      }

  def letFreeConvertGlobal(v: Global[Declaration], env: Env, p: Package.Inferred):
    NormState[TypedExpr[(Declaration, LetFreeExpressionTag)]] =
      env._1.get(v.name) match {
        case None => norm(p, v.name, v.tag, env).map { ne =>
          val lfeTag = getTag(ne)._2
          v.copy(tag=(v.tag, lfeTag))
        }
        case Some(lfeTag) =>
          State.pure(v.copy(tag=(v.tag, lfeTag)))
      }

  def letFreeConvertAnnotatedLambda(al: AnnotatedLambda[Declaration], env: Env, p: Package.Inferred):
    NormState[TypedExpr[(Declaration, LetFreeExpressionTag)]] = {
      val lambdaVars = al.arg :: env._2
      val env1 = env._1.mapValues { case ExpressionKeyTag(lfe, children) =>
        LetFreeExpressionTag(LetFreeConversion.incrementLambdaVars(lfe, -1), children)
      }
      val nextEnv: Env = (env1 ++ lambdaVars.zipWithIndex
        .reverse
        .toMap
        .mapValues(idx => LetFreeExpressionTag(LetFreeExpression.LambdaVar(idx), Set[LetFreeExpression]())),
        lambdaVars)
      for {
        eExpr <- letFreeConvertExpr(al.expr, nextEnv, p)
        ne = normalOrderReduction(LetFreeExpression.Lambda(eExpr.tag._2.lfe))
        children = combineWithChildren(eExpr.tag._2)
        lfeTag = LetFreeExpressionTag(ne, children)
      } yield al.copy(expr=eExpr, tag=(al.tag, lfeTag))
    }

  def letFreeConvertApp(a: App[Declaration], env: Env, p: Package.Inferred):
    NormState[TypedExpr[(Declaration, LetFreeExpressionTag)]] =
      for {
        efn <- letFreeConvertExpr(a.fn, env, p)
        earg <- letFreeConvertExpr(a.arg, env, p)
        ne = normalOrderReduction(LetFreeExpression.App(efn.tag._2.lfe, earg.tag._2.lfe))
        children = combineWithChildren(efn.tag._2) ++ combineWithChildren(earg.tag._2)
        lfeTag = LetFreeExpressionTag(ne, children)
      } yield a.copy(fn=efn, arg=earg, tag=(a.tag, lfeTag))

  def letFreeConvertLet(l: Let[Declaration], env: Env, p: Package.Inferred):
    NormState[TypedExpr[(Declaration, LetFreeExpressionTag)]] =
      l.recursive match {
        case RecursionKind.Recursive =>
          val lambdaVars = l.arg :: env._2
          val nextEnv = (env._1 ++ lambdaVars.zipWithIndex
            .reverse
            .toMap
            .mapValues(idx => LetFreeExpressionTag(LetFreeExpression.LambdaVar(idx), Set[LetFreeExpression]())),
            lambdaVars)
          val neWrapper = {ne: LetFreeExpression => normalOrderReduction(LetFreeExpression.Recursion(LetFreeExpression.Lambda(ne)))}
          val originalLambda = AnnotatedLambda(arg=l.arg, tpe=l.expr.getType, expr=l.in, tag=l.tag)
          for {
            ee <- letFreeConvertExpr(l.expr, nextEnv, p)
            eeNe = neWrapper(ee.tag._2.lfe)
            eeNeTag = LetFreeExpressionTag(eeNe, ee.tag._2.children)
            nextNextEnv: Env = (env._1 + (l.arg -> eeNeTag), env._2)
            eIn <- letFreeConvertExpr(l.in, nextNextEnv, p)
          } yield Let(l.arg, ee, eIn, l.recursive, (l.tag, eIn.tag._2))
        case _ =>
          for {
            ee <- letFreeConvertExpr(l.expr, env, p)
            nextEnv: Env = (env._1 + (l.arg -> ee.tag._2), env._2)
            eIn <- letFreeConvertExpr(l.in, nextEnv, p)
          } yield Let(l.arg, ee, eIn, l.recursive, (l.tag, eIn.tag._2))
      }

  def letFreeConvertLiteral(l: Literal[Declaration], env: Env, p: Package.Inferred): NormState[
    TypedExpr[(Declaration, LetFreeExpressionTag)]] =
      State.pure(l.copy(tag=(l.tag, LetFreeExpressionTag(LetFreeExpression.Literal(l.lit), Set()))))

  def letFreeConvertMatch(m: Match[Declaration], env: Env, p: Package.Inferred): NormState[
    TypedExpr[(Declaration, LetFreeExpressionTag)]] = for {
      arg <- letFreeConvertExpr(m.arg, env, p)
      branches <- (m.branches.map { case branch => letFreeConvertBranch(branch, env, p)}).sequence
      letFreeBranches = branches.map { case (p, e) => (letFreeConvertPattern(p), e.tag._2.lfe)}
      ne=normalOrderReduction(LetFreeExpression.Match(arg.tag._2.lfe, letFreeBranches))
      children=branches.foldLeft(combineWithChildren(arg.tag._2)) { case (tags, br) => tags ++ combineWithChildren(br._2.tag._2) }
      lfeTag = LetFreeExpressionTag(lfe=ne, children=children)
    } yield Match(arg=arg,
      branches=branches,
      tag=(m.tag, lfeTag))

  def letFreeConvertPattern(pat: Pattern[(PackageName, Constructor), Type]): LetFreePattern = {
    val names = pat.names
    def loop(pat: Pattern[(PackageName, Constructor), Type]): LetFreePattern =
      pat match {
        case Pattern.WildCard => LetFreePattern.WildCard
        case Pattern.Literal(lit) => LetFreePattern.Literal(lit)
        case Pattern.Var(v) => LetFreePattern.Var(names.indexOf(v))
        case Pattern.Named(n, p) => LetFreePattern.Named(names.indexOf(n), loop(p))
        case Pattern.StrPat(items) => LetFreePattern.StrPat(
          items.map {
            case Pattern.StrPart.WildStr => LetFreePattern.StrPart.WildStr
            case Pattern.StrPart.NamedStr(n) => LetFreePattern.StrPart.NamedStr(names.indexOf(n))
            case Pattern.StrPart.LitStr(asString) => LetFreePattern.StrPart.LitStr(asString)
          })
        case Pattern.ListPat(items) =>
          LetFreePattern.ListPat(items.map {
            case Pattern.ListPart.NamedList(n) =>Left(Some(names.indexOf(n)))
            case Pattern.ListPart.WildList => Left(None)
            case Pattern.ListPart.Item(p) => Right(loop(p))
          })
        case Pattern.Annotation(p, tpe) => loop(p)
        case Pattern.PositionalStruct(pc@(_, ctor), params) =>
          val dt = definedForCons(pc)
          val df = dt.dataFamily
          val name = if (dt.isStruct) None else Some(dt.constructors.indexWhere(_.name == ctor))
          LetFreePattern.PositionalStruct(name, params.map(loop(_)), df)
        case Pattern.Union(h, t) => LetFreePattern.Union(loop(h), t.map(loop(_)))
      }
    loop(pat)
  }

  def letFreeConvertBranch(b: (Pattern[(PackageName, Constructor), Type], TypedExpr[Declaration]), env: Env, p: Package.Inferred): NormState[
    (Pattern[(PackageName, Constructor), Type], TypedExpr[(Declaration, LetFreeExpressionTag)])] = {
    val (pattern, expr) = b
    val names = pattern.names.collect { case b: Identifier.Bindable => b }
    val lambdaVars = names ++ env._2
    val nextEnv = (env._1 ++ lambdaVars.zipWithIndex
      .reverse
      .toMap
      .mapValues(idx => LetFreeExpressionTag(LetFreeExpression.LambdaVar(idx), Set[LetFreeExpression]())),
      lambdaVars)
    for {
      innerExpr <- letFreeConvertExpr(expr, nextEnv, p)
      normalExpr = names.foldLeft(innerExpr.tag._2.lfe) { case (expr, _) => LetFreeExpression.Lambda(expr) }
      finalExpression = innerExpr.updatedTag((innerExpr.tag._1, innerExpr.tag._2.copy(lfe=normalExpr)))
    } yield (pattern, finalExpression)
  }

  def letFreeConvertProgram(pkgName: PackageName, pack: Package.Inferred): NormState[
    Program[TypeEnv[Variance], TypedExpr[(Declaration, LetFreeConversion.LetFreeExpressionTag)], Any]] = {
    for {
      lets <- pack.program.lets.map {
        case (name, recursive, expr) => letFreeConvertNameKindLet(name, recursive, expr, pack, (Map(), Nil)).map((name, recursive, _))
      }.sequence
    } yield pack.program.copy(
      lets  = lets
    )
  }

  def letFreeConvertPackage(pkgName: PackageName, pack: Package.Inferred):
    NormState[LetFreePac] = for {
    program <- letFreeConvertProgram(pkgName, pack)
  } yield pack.copy(program = program)

  def getTag(ref: ResultingRef) = ref match {
    case Right(te) => te.tag
    case Left((_, t)) => t
  }

  private type Ref[T] =
    Either[(Identifier, T), TypedExpr[T]]

  private type SourceRef = Ref[Declaration]
  private type ResultingRef = Ref[(Declaration,  LetFreeExpressionTag)]
  private type Env = (Map[Identifier, LetFreeExpressionTag], List[Identifier])
  private type NormState[A] = State[Map[(PackageName, Identifier), TypedExpr[(Declaration, LetFreeExpressionTag)]], A]

  private def norm(pack: Package.Inferred, item: Identifier, t: Declaration, env: Env): NormState[ResultingRef] =
      NameKind(pack, item).get match { // this get should never fail due to type checking
        case NameKind.Let(name, recursive, expr) => letFreeConvertNameKindLet(
          name, recursive, expr, pack, env
          ).map(res => Right(res))
        case NameKind.Constructor(cn, _, dt, _) =>
          val lfeTag = LetFreeExpressionTag(constructor(cn, dt), Set())
          State.pure(Left((item, (t, lfeTag))))
        case NameKind.Import(from, orig) =>
          // we reset the environment in the other package
          for {
            imported <- norm(pm.toMap(from.name), orig, t, (Map.empty, Nil))
            lfeTag = getTag(imported)._2
          } yield Left((item, (t, lfeTag)))
        case NameKind.ExternalDef(pn, n, defType) =>
          val lfeTag = LetFreeExpressionTag(LetFreeExpression.ExternalVar(pn, n, defType), Set())  
          State.pure(Left((item, (t, lfeTag))))
      }

  private def letFreeConvertNameKindLet(name: Identifier.Bindable, recursive: RecursionKind, expr: TypedExpr[Declaration], pack: Package.Inferred, env: Env):
    NormState[TypedExpr[(Declaration, LetFreeExpressionTag)]] =
    for {
      lookup <- State.inspect {
        lets: Map[(PackageName, Identifier), TypedExpr[(Declaration, LetFreeExpressionTag)]] =>
          lets.get((pack.name, name))
        }
      outExpr  <- lookup match {
        case Some(res) =>
          State.pure(res): NormState[TypedExpr[(Declaration, LetFreeExpressionTag)]]
        case None =>
          recursive match {
            case RecursionKind.Recursive =>
              val lambdaVars = name :: env._2
              val nextEnv = (env._1 ++ lambdaVars.zipWithIndex
                .toMap
                .mapValues(idx => LetFreeExpressionTag(LetFreeExpression.LambdaVar(idx), Set[LetFreeExpression]())),
                lambdaVars)
              for {
                res <- letFreeConvertExpr(expr, nextEnv, pack)
                tag = res.tag
                wrappedNe = normalOrderReduction(LetFreeExpression.Recursion(LetFreeExpression.Lambda(tag._2.lfe)))
                children = tag._2.children
                finalRes = res.updatedTag((res.tag._1, LetFreeExpressionTag(wrappedNe, children)))
                _ <- State.modify {
                  lets: Map[(PackageName, Identifier), TypedExpr[(Declaration, LetFreeExpressionTag)]] =>
                    lets + ((pack.name, name) -> finalRes)
                }
              } yield finalRes
            case _ =>
              for {
                res <- letFreeConvertExpr(expr, env, pack)
                _ <- State.modify {
                  lets: Map[(PackageName, Identifier), TypedExpr[(Declaration, LetFreeExpressionTag)]] =>
                    lets + ((pack.name, name) -> res)
                }
              } yield res
          }
      }
    } yield outExpr

  private def constructor(c: Constructor, dt: rankn.DefinedType[Any]): LetFreeExpression = {
      val (enum, arity) = dt.constructors
        .toList
        .iterator
        .zipWithIndex
        .collectFirst { case (cf, idx) if cf.name == c => (idx, cf.args.size) }
        .get

      def loop(params: Int, expr: LetFreeExpression): LetFreeExpression =
        if (params == 0) expr
        else loop(params - 1, LetFreeExpression.Lambda(expr))

        loop(arity, LetFreeExpression.Struct(enum, ((arity - 1) to 0 by -1).map(LetFreeExpression.LambdaVar(_)).toList, dt.dataFamily))
  }

  private def definedForCons(pc: (PackageName, Constructor)): DefinedType[Any] =
    pm.toMap(pc._1).program.types.getConstructor(pc._1, pc._2).get._2
}

