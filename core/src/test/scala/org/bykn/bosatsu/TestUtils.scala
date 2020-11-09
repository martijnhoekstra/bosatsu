package org.bykn.bosatsu

import cats.Eval
import cats.data.{Ior, Validated}
import cats.implicits._
import org.bykn.bosatsu.rankn._
import org.scalatest.{Assertion, Assertions}
import scala.concurrent.ExecutionContext

import Assertions.{succeed, fail}
import IorMethods.IorExtension
import org.bykn.bosatsu.LetFreeEvaluation.LazyValue

object TestUtils {

  def typeEnvOf(pack: PackageName, str: String): TypeEnv[Unit] = {

    val tpeFn: Identifier.Constructor => Type.Const =
      { tpe => Type.Const.Defined(pack, TypeName(tpe)) }

    val consFn: Identifier.Constructor => (PackageName, Identifier.Constructor) =
      { cons => (pack, cons) }

    val stmt = statementsOf(pack, str)
    val srcConv = SourceConverter(pack, Nil, Statement.definitionsOf(stmt))
    val Ior.Right(prog) = srcConv.toProgram(stmt)
    TypeEnv.fromParsed(prog.types._2)
  }

  def statementsOf(pack: PackageName, str: String): List[Statement] =
    Parser.unsafeParse(Statement.parser, str)

  /**
   * Make sure no illegal final types escaped into a TypedExpr
   */
  def assertValid[A](te: TypedExpr[A]): Unit = {
    def checkType(t: Type): Type =
      t match {
        case t@Type.TyVar(Type.Var.Skolem(_, _)) =>
          sys.error(s"illegal skolem ($t) escape in ${te.repr}")
        case Type.TyVar(Type.Var.Bound(_)) => t
        case t@Type.TyMeta(_) =>
          sys.error(s"illegal meta ($t) escape in ${te.repr}")
        case Type.TyApply(left, right) =>
          Type.TyApply(checkType(left), checkType(right))
        case Type.ForAll(args, in) =>
          Type.ForAll(args, checkType(in).asInstanceOf[Type.Rho])
        case Type.TyConst(_) => t
      }
    te.traverseType[cats.Id](checkType)
    val tp = te.getType
    lazy val teStr = TypeRef.fromTypes(None, tp :: Nil)(tp).toDoc.render(80)
    scala.Predef.require(Type.freeTyVars(tp :: Nil).isEmpty,
      s"illegal inferred type: $teStr in: ${te.repr}")

    scala.Predef.require(Type.metaTvs(tp :: Nil).isEmpty,
      s"illegal inferred type: $teStr in: ${te.repr}")
  }

  val testPackage: PackageName = PackageName.parts("Test")

  def checkLast(statement: String)(fn: TypedExpr[Declaration] => Assertion): Assertion = {
    val stmts = Parser.unsafeParse(Statement.parser, statement)
    Package.inferBody(testPackage, Nil, stmts).strictToValidated match {
      case Validated.Invalid(errs) =>
        fail("inference failure: " + errs.toList.map(_.message(Map.empty, LocationMap.Colorize.None)).mkString("\n"))
      case Validated.Valid(program) =>
        // make sure all the TypedExpr are valid
        program.lets.foreach { case (_, _, te) => assertValid(te) }
        fn(program.lets.last._3)
    }
  }

  def makeInputArgs(files: List[(Int, Any)]): List[String] =
    ("--package_root" :: Int.MaxValue.toString :: Nil) ::: files.flatMap { case (idx, _) => "--input" :: idx.toString :: Nil }

  private val module = new MemoryMain[Either[Throwable, ?], Int]({ idx =>
    if (idx == Int.MaxValue) Nil
    else List(s"Package$idx")
  })

  def evalTest(packages: List[String], mainPackS: String, expected: Value) = {
    val files = packages.zipWithIndex.map(_.swap)

    module.runWith(files)("eval" :: "--main" :: mainPackS :: makeInputArgs(files)) match {
      case Right(module.Output.EvaluationResult(got, _, gotDoc)) =>
        val gv = got.value
        assert(gv == expected, s"${gotDoc.value.render(80)}\n\n$gv != $expected")
      case Right(other) =>
        fail(s"got an unexpected success: $other")
      case Left(err) =>
        fail(s"got an exception: $err")
    }
  }

  def evalTestJson(packages: List[String], mainPackS: String, expected: Json) = {
    val files = packages.zipWithIndex.map(_.swap)

    module.runWith(files)("json" :: "write" :: "--main" :: mainPackS :: "--output" :: "-1" :: makeInputArgs(files)) match {
      case Right(module.Output.JsonOutput(got, _)) =>
        assert(got == expected, s"$got != $expected")
      case Right(other) =>
        fail(s"got an unexpected success: $other")
      case Left(err) =>
        fail(s"got an exception: $err")
    }
  }

  def runBosatsuTest(packages: List[String], mainPackS: String, assertionCount: Int) = {
    val files = packages.zipWithIndex.map(_.swap)

    module.runWith(files)("test" :: "--test_package" :: mainPackS :: makeInputArgs(files)) match {
      case Right(module.Output.TestOutput(results, _)) =>
        results.collect { case (_, Some(t)) => t.value } match {
          case t :: Nil =>
            assert(t.assertions == assertionCount, s"${t.assertions} != $assertionCount")
            val (suc, failcount, message) = Test.report(t, LocationMap.Colorize.None)
            assert(t.failures.map(_.assertions).getOrElse(0) == failcount)
            if (failcount > 0) fail(message.render(80))
            else succeed
          case other =>
            fail(s"expected exactly one test result, got: $other")
        }
      case Right(other) =>
        fail(s"got an unexpected success: $other")
      case Left(err) =>
        fail(s"got an exception: $err")
    }
  }

  def testInferred(packages: List[String], mainPackS: String, inferredHandler: (PackageMap.Inferred, PackageName) => Assertion ) = {
    val mainPack = PackageName.parse(mainPackS).get

    val parsed = packages.zipWithIndex.traverse { case (pack, i) =>
      Parser.parse(Package.parser(None), pack).map { case (lm, parsed) =>
        ((i.toString, lm), parsed)
      }
    }

    val parsedPaths = parsed match {
      case Validated.Valid(vs) => vs
      case Validated.Invalid(errs) =>
        errs.toList.foreach { p =>
          p.showContext(LocationMap.Colorize.None).foreach(System.err.println)
        }
        sys.error("failed to parse") //errs.toString)
    }

    // use parallelism to typecheck
    import ExecutionContext.Implicits.global

    val fullParsed =
        Predef.withPredefA(("predef", LocationMap("")), parsedPaths)
          .map { case ((path, lm), p) => (path, p) }

    PackageMap
      .resolveThenInfer(fullParsed , Nil).strictToValidated match {
        case Validated.Valid(packMap) =>
          inferredHandler(packMap, mainPack)

        case Validated.Invalid(errs) =>
          val tes = errs.toList.collect {
            case PackageError.TypeErrorIn(te, _) =>
              te.message
          }
          .mkString("\n")
          fail(tes + "\n" + errs.toString)
      }
  }
  sealed abstract class NormalTestMode

  object NormalTestMode {
    case class TagMode(expected: LetFreeConversion.LetFreeExpressionTag) extends NormalTestMode
    case class ExpressionMode(expected: LetFreeExpression, extraAssertions: List[LetFreeExpression => Assertion]) extends NormalTestMode
    case class VarSetMode(lambdaCount: Int, vars: Set[Int]) extends NormalTestMode
  }

  def unwrapLambda(expression: LetFreeExpression, lambdaCount: Int): Option[LetFreeExpression] =
    if (lambdaCount <= 0) {
      Some(expression)
    } else {
      expression match {
        case LetFreeExpression.Lambda(expr) => unwrapLambda(expr, lambdaCount - 1)
        case _ => None
      }
    }

  def normalizeTest(
      packages: List[String],
      mainPackS: String,
      expectedMode: NormalTestMode
  ) = {
    def inferredHandler(
        infPackMap: PackageMap.Inferred,
        mainPack: PackageName
    ): Assertion =
      (for {
        pack <-
          LetFreePackageMap(infPackMap).letFreePackageMap.toMap.get(mainPack)
        exprs = pack.program.lets.map { case (_, rec, e) => e }
        fleft = exprs.map(_.size.toInt)
        fright = exprs.map(_.foldRight(Eval.now(0)) {
          case (_, m) => m.map(_ + 1)
        }.value)
        expr <- exprs.lastOption
        tag = expr.tag
        ne = tag._2.lfe
        children = tag._2.children
      } yield {
        assert(
          fleft == fright,
          s"folds didn't match. left: $fleft, right: $fright"
        )
        expectedMode match {
          case NormalTestMode.TagMode(expected) =>
            assert(
              ne == expected.lfe,
              s"ne error. expected '${expected.lfe}' got '$ne'"
            )
            assert(
              children == expected.children,
              s"children error. expected '${expected.children}' got '$children'"
            )
            succeed
          case NormalTestMode.ExpressionMode(expected, extraAssertions) =>
            assert(
              ne == expected,
              s"ne error. expected '${expected}' got '$ne'"
            )
            extraAssertions.foreach(_.apply(ne))
            succeed
          case NormalTestMode.VarSetMode(lambdaCount, vars) =>
            unwrapLambda(ne, lambdaCount) match {
              case Some(expr) => {
                assert(
                  expr.varSet == vars,
                  s"expected: $vars, got: ${expr.varSet}"
                )
                succeed
              }
              case None =>
                fail(s"Could not unwrap expression $lambdaCount times: $ne")
            }
        }
      }).getOrElse(fail("There should be a last expression"))

    testInferred(packages, mainPackS, inferredHandler(_, _))
  }

  def normalTagTest(packages: List[String], mainPackS: String, expected: LetFreeConversion.LetFreeExpressionTag) =
    normalizeTest(packages, mainPackS, NormalTestMode.TagMode(expected))
  def normalExpressionTest(packages: List[String], mainPackS: String, expected: LetFreeExpression, extraAssertions: List[LetFreeExpression => Assertion] = Nil) =
    normalizeTest(packages, mainPackS, NormalTestMode.ExpressionMode(expected, extraAssertions))
  def letFreeVarSetTest(packages: List[String], mainPackS: String, lambdaCount: Int, vars: Set[Int]) =
    normalizeTest(packages, mainPackS, NormalTestMode.VarSetMode(lambdaCount, vars)
    )
  def letFreeEvaluateTest(packages: List[String], mainPackS: String, ext: Externals, assertions: List[LazyValue => Assertion]) = {
    def inferredHandler(infPackMap: PackageMap.Inferred, mainPack: PackageName): Assertion = {
      val normPackMap = LetFreePackageMap(infPackMap).letFreePackageMap
      LetFreeEvaluation(normPackMap, Predef.jvmExternals ++ ext).evaluateLast(mainPack) match {
        case Some(res) => {
          val value = LazyValue(res._1, Nil)(res._3, None)
          
          assertions.foreach(_.apply(value))
          succeed
        }
        case None => fail("There should be a last expression")
      }
    }

    testInferred(packages, mainPackS, inferredHandler(_,_))
  }

  def evalFail(packages: List[String], mainPackS: String, extern: Externals = Externals.empty)(errFn: PartialFunction[PackageError, Unit]) = {
    val mainPack = PackageName.parse(mainPackS).get

    val parsed = packages.zipWithIndex.traverse { case (pack, i) =>
      Parser.parse(Package.parser(None), pack).map { case (lm, parsed) =>
        ((i.toString, lm), parsed)
      }
    }

    val parsedPaths = parsed match {
      case Validated.Valid(vs) => vs
      case Validated.Invalid(errs) =>
        sys.error(errs.toString)
    }

    // use parallelism to typecheck
    import ExecutionContext.Implicits.global
    val withPre =
      Predef.withPredefA(("predef", LocationMap("")), parsedPaths)

    val withPrePaths = withPre.map { case ((path, _), p) => (path, p) }
    PackageMap.resolveThenInfer(withPrePaths, Nil).left match {
      case None =>
        fail("expected to fail type checking")

      case Some(errs) if errs.collect(errFn).nonEmpty =>
        // make sure we can print the messages:
        val sm = PackageMap.buildSourceMap(withPre)
        errs.toList.foreach(_.message(sm, LocationMap.Colorize.None))
        assert(true)
      case Some(errs) =>
          fail(s"failed, but no type errors: $errs")
    }
  }


}
