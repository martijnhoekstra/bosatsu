package org.bykn.bosatsu

import cats.arrow.FunctionK
import cats.data.{Chain, Validated, ValidatedNel, NonEmptyList}
import cats.{Eval, MonadError, Traverse}
import com.monovore.decline.{Argument, Command, Help, Opts}
import fastparse.all.P
import org.typelevel.paiges.Doc
import scala.util.{ Failure, Success, Try }

import LocationMap.Colorize

import cats.implicits._

/**
 * This is an implementation of the CLI tool where Path is abstracted.
 * The idea is to allow it to be testable and usable in scalajs where
 * we don't have file-IO
 */
abstract class MainModule[IO[_]](implicit val moduleIOMonad: MonadError[IO, Throwable]) {
  type Path

  implicit def pathArg: Argument[Path]

  def readPath(p: Path): IO[String]

  def readPackages(paths: List[Path]): IO[List[Package.Typed[Unit]]]

  def readInterfaces(paths: List[Path]): IO[List[Package.Interface]]

  /**
   * given an ordered list of prefered roots, if a packFile starts
   * with one of these roots, return a PackageName based on the rest
   */
  def pathPackage(roots: List[Path], packFile: Path): Option[PackageName]

  /**
   * Modules optionally have the capability to combine paths into
   * a tree
   */
  def resolvePath: Option[(Path, PackageName) => IO[Option[Path]]]

  /**
   * some modules have paths that form directory trees
   *
   * if the given path is a directory, return Some and
   * all the first children.
   */
  def unfoldDir: Option[Path => IO[Option[IO[List[Path]]]]]

  def hasExtension(str: String): Path => Boolean

  //////////////////////////////
  // Below here are concrete and should not use override
  //////////////////////////////

  final def run(args: List[String]): Either[Help, IO[Output]] =
    MainCommand.command
      .parse(args.toList)
      .map(_.run)

  sealed abstract class Output
  object Output {
    case class TestOutput(tests: List[(PackageName, Option[Test])], colorize: Colorize) extends Output
    case class EvaluationResult(value: Eval[Value], tpe: rankn.Type) extends Output {
      def toJson[A](ev: Evaluation[A], outputOpt: Option[Path]): IO[JsonOutput] =
        ev.toJson(value.value, tpe) match {
          case None =>
            val tpeStr = TypeRef.fromTypes(None, tpe :: Nil)(tpe).toDoc.render(80)
            moduleIOMonad.raiseError(new Exception(
              s"cannot convert type to Json: $tpeStr"))
          case Some(j) =>
            moduleIOMonad.pure(Output.JsonOutput(j, outputOpt))
        }
    }
    case class JsonOutput(json: Json, output: Option[Path]) extends Output
    case class CompileOut(packList: List[Package.Typed[Any]], ifout: Option[Path], output: Path) extends Output
  }

  sealed abstract class MainCommand {
    def run: IO[Output]
  }

  object MainCommand {
    def parseInputs[F[_]: Traverse](paths: F[Path], packRes: PackageResolver): IO[ValidatedNel[ParseError, F[((Path, LocationMap), Package.Parsed)]]] =
      // we use IO(traverse) so we can accumulate all the errors in parallel easily
      // if do this with parseFile returning an IO, we need to do IO.Par[Validated[...]]
      // and use the composed applicative... too much work for the same result
      paths.traverse { path =>
        val defaultPack = packRes.packageNameFor(path)
        parseFile(Package.parser(defaultPack), path)
          .map(_.map { case (lm, parsed) =>
            ((path, lm), parsed)
          })
      }
      .map(_.sequence)

    private def flatTrav[A, B, C](va: Validated[A, B])(fn: B => IO[Validated[A, C]]): IO[Validated[A, C]] =
      va.traverse(fn).map(_.andThen(identity _))

    /**
     * This parses all the given paths and returns them first, and if the PackageResolver supports
     * it, we look for any missing dependencies that are not already included
     */
    def parseAllInputs(paths: List[Path], included: Set[PackageName], packRes: PackageResolver): IO[ValidatedNel[ParseError, List[((Path, LocationMap), Package.Parsed)]]] =
      parseInputs(paths, packRes)
        .flatMap {
          flatTrav(_) { parsed =>
            val done = included ++ parsed.toList.map(_._2.name)
            val allImports = parsed.toList.flatMap(_._2.imports.map(_.pack))
            val missing: List[PackageName] = allImports.filterNot(done)
            parseTransitivePacks(missing, packRes, done)
              .map(_.map { case (searched, _) => parsed ::: searched.toList })
          }
        }


    type ParseTransResult = ValidatedNel[ParseError, (Chain[((Path, LocationMap), Package.Parsed)], Set[PackageName])]

    private def parseTransitive(
      search: PackageName,
      packRes: PackageResolver,
      done: Set[PackageName]): IO[ParseTransResult] = {

      def maybeRead(p: Path): IO[Option[String]] =
        readPath(p).map(Option(_)).recover { case _ => None }

      val maybeReadPack: IO[Option[(Path, String)]] =
        if (done(search)) {
          moduleIOMonad.pure(Option.empty[(Path, String)])
        }
        else {
          packRes
            .pathFor(search)
            .flatMap(_.traverse { path =>
              readPath(path).map((path, _))
            })
        }

      val optParsed: IO[ValidatedNel[ParseError, Option[((Path, LocationMap), Package.Parsed)]]] =
        maybeReadPack.map { opt =>
          opt.traverse { case (path, str) =>
            val defaultPack = packRes.packageNameFor(path)
            parseString(Package.parser(defaultPack), path, str)
              .map { case (lm, parsed) =>
                ((path, lm), parsed)
              }
          }
        }

      def imports(p: Package.Parsed): List[PackageName] =
        p.imports.map(_.pack)

      val newDone = done + search

      optParsed.flatMap {
        flatTrav(_) {
          case None =>
            moduleIOMonad.pure(
              Validated.valid(
                (Chain.empty[((Path, LocationMap), Package.Parsed)], newDone)
              ): ParseTransResult
            )
          case Some(item@(plm, pack)) =>
            val imps = imports(pack).filterNot(done)
            parseTransitivePacks(imps, packRes, newDone)
              .map(_.map { case (newPacks, newDone) => (item +: newPacks, newDone) })
        }
      }
    }

    private def parseTransitivePacks(
      search: List[PackageName],
      packRes: PackageResolver,
      done: Set[PackageName]): IO[ParseTransResult] =
        search.foldM(Validated.valid((Chain.empty, done)): ParseTransResult) { (prev, impPack) =>
          flatTrav(prev) { case (acc, prevDone) =>
            parseTransitive(impPack, packRes, prevDone)
              .map(_.map {
                case (newPacks, newDone) => (acc ++ newPacks, newDone)
              })
          }
        }

    sealed trait ParseError {
      def showContext(errColor: Colorize): Option[Doc] =
        this match {
          case ParseError.PartialParse(err, _) =>
            err.showContext(errColor)
          case ParseError.ParseFailure(err, _) =>
            err.showContext(errColor)
          case ParseError.FileError(_, _) =>
            None
        }
    }

    object ParseError {
       case class PartialParse[A](error: Parser.Error.PartialParse[A], path: Path) extends ParseError
       case class ParseFailure(error: Parser.Error.ParseFailure, path: Path) extends ParseError
       case class FileError(readPath: Path, error: Throwable) extends ParseError
    }

    def parseString[A](p: P[A], path: Path, str: String): ValidatedNel[ParseError, (LocationMap, A)] =
      Parser.parse(p, str).leftMap { nel =>
        nel.map {
          case pp@Parser.Error.PartialParse(_, _, _) => ParseError.PartialParse(pp, path)
          case pf@Parser.Error.ParseFailure(_, _) => ParseError.ParseFailure(pf, path)
        }
      }

    def parseFile[A](p: P[A], path: Path): IO[ValidatedNel[ParseError, (LocationMap, A)]] =
      parseFileOrError(p, path)
        .map {
          case Right(v) => v
          case Left(err) => Validated.invalidNel(ParseError.FileError(path, err))
        }

    /**
     * If we cannot read the file, return the throwable, else parse
     */
    def parseFileOrError[A](p: P[A], path: Path): IO[Either[Throwable, ValidatedNel[ParseError, (LocationMap, A)]]] =
      readPath(path)
        .attempt
        .map(_.map(parseString(p, path, _)))

    /**
     * like typecheck, but a no-op for empty lists
     */
    def typeCheck0(
      inputs: List[Path],
      ifs: List[Package.Interface],
      errColor: Colorize,
      packRes: PackageResolver
      ): IO[(PackageMap.Inferred, List[(Path, PackageName)])] =
      NonEmptyList.fromList(inputs) match {
        case None =>
          // we should still return the predef
          // if it is not in ifs
          val useInternalPredef =
            !ifs.contains { p: Package.Interface => p.name == PackageName.PredefName }

          if (useInternalPredef) {
            moduleIOMonad.pure((PackageMap.fromIterable(Predef.predefCompiled :: Nil), Nil))
          }
          else {
            moduleIOMonad.pure((PackageMap.empty, Nil))
          }
        case Some(nel) => typeCheck(nel, ifs, errColor, packRes)
      }

    def typeCheck(
      inputs: NonEmptyList[Path],
      ifs: List[Package.Interface],
      errColor: Colorize,
      packRes: PackageResolver
      ): IO[(PackageMap.Inferred, List[(Path, PackageName)])] =
      parseAllInputs(inputs.toList, ifs.map(_.name).toSet, packRes)
        .flatMap { ins =>
          moduleIOMonad.fromTry {
            // Now we have completed all IO, here we do all the checks we need for correctness
            toTry(ins, errColor)
              .flatMap { packs =>
                val map = PackageMap.buildSourceMap(packs)
                val liftError = Lambda[FunctionK[ValidatedNel[PackageError, ?], Try]](fromPackageError(map, _, errColor))
                // TODO, we could use applicative, to report both duplicate packages and the other
                // errors
                NonEmptyList.fromList(packs) match {
                  case Some(packs) =>
                    // TODO: this use the number of cores in the threadpool but we could configure
                    // this
                    import scala.concurrent.ExecutionContext.Implicits.global
                    PackageMap.typeCheckParsed(packs, ifs, "predef", liftError)(checkDuplicatePackages(_)(_._1.toString))
                      .map { p =>
                        val pathToName: List[(Path, PackageName)] =
                          packs.map { case ((path, _), p) => (path, p.name) }.toList
                        (p, pathToName)
                      }
                  case None =>
                    Success((PackageMap.empty, Nil))
                }
              }
          }
        }

    def buildPackMap(
      srcs: List[Path],
      deps: List[Path],
      errColor: Colorize,
      packRes: PackageResolver): IO[(PackageMap.Typed[Any], List[(Path, PackageName)])] =
        for {
          packs <- readPackages(deps)
          ifaces = packs.map(Package.interfaceOf(_))
          packsList <- typeCheck0(srcs, ifaces, errColor, packRes)
          (thesePacks, lst) = packsList
          packMap = packs.foldLeft(PackageMap.toAnyTyped(thesePacks))(_ + _)
        } yield (packMap, lst)

    /**
     * This allows us to use either a path or packagename to select
     * the main file
     */
    sealed abstract class MainIdentifier {
      def path: Option[Path]
      def addIfAbsent(paths: List[Path]): List[Path] =
        path match {
          case Some(p) if !paths.contains(p) => p :: paths
          case _ => paths
        }
      def getMain(ps: List[(Path, PackageName)]): IO[PackageName]
    }
    object MainIdentifier {
      case class FromPackage(mainPackage: PackageName) extends MainIdentifier {
        def path: Option[Path] = None
        def getMain(ps: List[(Path, PackageName)]): IO[PackageName] = moduleIOMonad.pure(mainPackage)
      }
      case class FromFile(mainFile: Path) extends MainIdentifier {
        def path: Option[Path] = Some(mainFile)
        def getMain(ps: List[(Path, PackageName)]): IO[PackageName] =
          ps.collectFirst { case (path, pn) if path == mainFile => pn } match {
            case None => moduleIOMonad.raiseError(new Exception(s"could not find file $mainFile in parsed sources"))
            case Some(p) => moduleIOMonad.pure(p)
          }
      }

      def opts(pnOpts: Opts[PackageName], fileOpts: Opts[Path]): Opts[MainIdentifier] =
        pnOpts.map(FromPackage(_)).orElse(fileOpts.map(FromFile(_)))

      def list(packs: Opts[List[PackageName]], files: Opts[List[Path]]): Opts[List[MainIdentifier]] =
        (packs, files).mapN { (ps, fs) =>
          ps.map(FromPackage(_)) ::: fs.map(FromFile(_))
        }

      def addAnyAbsent(ms: List[MainIdentifier], paths: List[Path]): List[Path] = {
        val present = paths.toSet
        val toAdd = ms.iterator.flatMap(_.path).filterNot(present).toList
        toAdd ::: paths
      }
    }

    /**
     * This is a class that names packages based on path
     * and finds packages based on imports
     */
    sealed abstract class PackageResolver {
      def pathFor(name: PackageName): IO[Option[Path]]
      def packageNameFor(path: Path): Option[PackageName]
    }
    object PackageResolver {
      case object ExplicitOnly extends PackageResolver {
        def pathFor(name: PackageName): IO[Option[Path]] = moduleIOMonad.pure(Option.empty[Path])
        def packageNameFor(path: Path): Option[PackageName] = None
      }

      case class LocalRoots(roots: NonEmptyList[Path], optResolvePath: Option[(Path, PackageName) => IO[Option[Path]]]) extends PackageResolver {
        def pathFor(name: PackageName): IO[Option[Path]] =
          optResolvePath match {
            case None => moduleIOMonad.pure(Option.empty[Path])
            case Some(resolvePath) =>
              def step(p: List[Path]): IO[Either[List[Path], Option[Path]]] =
                p match {
                  case Nil =>
                    moduleIOMonad.pure(Right[List[Path], Option[Path]](None))
                  case phead :: ptail =>
                    resolvePath(phead, name).map {
                      case None => Left[List[Path], Option[Path]](ptail)
                      case some@Some(_) => Right[List[Path], Option[Path]](some)
                    }
                }

              moduleIOMonad.tailRecM(roots.toList)(step)
          }

        def packageNameFor(path: Path): Option[PackageName] =
          pathPackage(roots.toList, path)
      }
    }

    type PathGen = org.bykn.bosatsu.PathGen[IO, Path]
    val PathGen = org.bykn.bosatsu.PathGen

    case class Evaluate(
      inputs: PathGen,
      mainPackage: MainIdentifier,
      deps: PathGen,
      errColor: Colorize,
      packRes: PackageResolver) extends MainCommand {

      def runEval: IO[(Evaluation[Any], Output.EvaluationResult)] =
        for {
          ins <- inputs.read
          ds <- deps.read
          pn <- buildPackMap(mainPackage.addIfAbsent(ins), ds, errColor, packRes)
          (packs, names) = pn
          mainPackageName <- mainPackage.getMain(names)
          out <- if (packs.toMap.contains(mainPackageName)) {
                    val ev = Evaluation(packs, Predef.jvmExternals)
                    ev.evaluateLast(mainPackageName) match {
                      case None => moduleIOMonad.raiseError(new Exception("found no main expression"))
                      case Some((eval, tpe)) =>
                        moduleIOMonad.pure((ev, Output.EvaluationResult(eval, tpe)))
                    }
                  }
                  else {
                    moduleIOMonad.raiseError(new Exception(s"package ${mainPackageName.asString} not found"))
                  }
        } yield out

      def run = runEval.map(_._2: Output)
    }

    case class ToJson(
      inputs: PathGen,
      deps: PathGen,
      mainPackage: MainIdentifier,
      outputOpt: Option[Path],
      errColor: Colorize,
      packRes: PackageResolver) extends MainCommand {

      def run =
        Evaluate(inputs, mainPackage, deps, errColor, packRes)
          .runEval
          .flatMap { case (ev, res) =>
            res.toJson(ev, outputOpt).widen[Output]
          }
    }

    case class TypeCheck(
      inputs: PathGen,
      ifaces: PathGen,
      output: Path,
      ifout: Option[Path],
      errColor: Colorize,
      packRes: PackageResolver) extends MainCommand {
      def run =
        for {
          ins <- inputs.read
          ifpaths <- ifaces.read
          ifs <- readInterfaces(ifpaths)
          ins1 <- NonEmptyList.fromList(ins).fold(moduleIOMonad.raiseError[NonEmptyList[Path]](new Exception("no source files found")))(moduleIOMonad.pure(_))
          packPath <- typeCheck(ins1, ifs, errColor, packRes)
          packs = packPath._1
          packList =
              packs.toMap
                .iterator
                .map { case (_, p) => p }
                // TODO currently we recompile predef in every run, so every interface includes
                // predef, we filter that out
                .filter(_.name != PackageName.PredefName)
                .toList
                .sortBy(_.name)
        } yield Output.CompileOut(packList, ifout, output)
    }

    case class RunTests(
      tests: PathGen,
      testPacks: List[MainIdentifier],
      dependencies: PathGen,
      errColor: Colorize,
      packRes: PackageResolver) extends MainCommand {

      def run =
        tests.read
          .product(dependencies.read)
          .flatMap { case (testPaths, dependencies) =>
            val tests1 = MainIdentifier.addAnyAbsent(testPacks, testPaths)
              if (tests1.isEmpty && dependencies.isEmpty) {
                moduleIOMonad.raiseError(new Exception("no test sources or test dependencies"))
              }
              else {
                val typeChecked = buildPackMap(tests1, dependencies, errColor, packRes)

                val withTestPackNames = typeChecked
                  .flatMap { case (packs, nameMap) =>

                    testPacks.traverse(_.getMain(nameMap))
                      .map { testPackNames: List[PackageName] =>
                        (packs, nameMap, testPackNames)
                      }
                  }

                withTestPackNames.map { case (packs, nameMap, testPackNames) =>
                  val testIt: Iterator[PackageName] =
                    if (testPacks.isEmpty) {
                      // if there are no given files or packages to test, assume
                      // we test all the files
                      nameMap.iterator.map(_._2)
                    }
                    else {
                      // otherwise we have a specific list packages/files to test
                      testPackNames.iterator
                    }

                  val testPackages: List[PackageName] =
                    testIt
                      .toList
                      .sorted
                      .distinct
                  val ev = Evaluation(packs, Predef.jvmExternals)
                  val res0 = testPackages.map { p => (p, ev.evalTest(p)) }
                  val res =
                    if (testPacks.isEmpty) res0.filter { case (_, testRes) => testRes.isDefined }
                    else res0

                  Output.TestOutput(res, errColor)
                }
              }
          }
    }

    def errors(msgs: List[String]): Try[Nothing] =
      Failure(new Exception(msgs.mkString("\n######\n")))

    def toTry[A](v: ValidatedNel[ParseError, A], color: Colorize): Try[A] =
      v match {
        case Validated.Valid(a) => Success(a)
        case Validated.Invalid(errs) =>
          val msgs = errs.toList.flatMap {
            case ParseError.PartialParse(pp, path) =>
              // we should never be partial here
              val (r, c) = pp.locations.toLineCol(pp.position).get
              val ctx = pp.locations.showContext(pp.position, 2, color).get
              List(s"failed to parse completely $path at line ${r + 1}, column ${c + 1}",
                  ctx.render(80))
            case ParseError.ParseFailure(pf, path) =>
              // we should never be partial here
              val (r, c) = pf.locations.toLineCol(pf.position).get
              val ctx = pf.locations.showContext(pf.position, 2, color).get
              List(s"failed to parse $path at line ${r + 1}, column ${c + 1}",
                  ctx.render(80))
            case ParseError.FileError(path, err) =>
              err match {
                case e if e.getClass.getName == "java.nio.file.NoSuchFileException" =>
                  // This class isn't present in scalajs, use the String
                  List(s"file not found: $path")
                case _ =>
                  List(s"failed to parse $path",
                      err.getMessage,
                      err.getClass.toString)
              }
          }
        errors(msgs)
      }

    def fromPackageError[A](
      sourceMap: Map[PackageName, (LocationMap, String)],
      v: ValidatedNel[PackageError, A],
      errColor: Colorize): Try[A] =
        v match {
          case Validated.Invalid(errs) => errors(errs.map(_.message(sourceMap, errColor)).toList)
          case Validated.Valid(a) => Success(a)
        }

    def checkDuplicatePackages[A](dups: Map[PackageName, ((A, Package.Parsed), NonEmptyList[(A, Package.Parsed)])])(fn: A => String): Try[Unit] =
      if (dups.isEmpty) Success(())
      else
        errors(
          dups.iterator.map { case (pname, ((src, _), nelist)) =>
            val dupsrcs = (fn(src) :: nelist.map { case (s, _) => fn(s) }.toList).sorted.mkString(", ")
            s"package ${pname.asString} duplicated in $dupsrcs"
          }.toList
        )

    val opts: Opts[MainCommand] = {
      implicit val argPack: Argument[PackageName] =
        new Argument[PackageName] {
          def defaultMetavar: String = "packageName"
          def read(string: String): ValidatedNel[String, PackageName] =
            PackageName.parse(string) match {
              case Some(pn) => Validated.valid(pn)
              case None => Validated.invalidNel(s"could not parse $string as a package name. Must be capitalized strings separated by /")
            }
        }

      def toList[A](neo: Opts[NonEmptyList[A]]): Opts[List[A]] = {
        neo.orNone.map {
          case None => Nil
          case Some(ne) => ne.toList
        }
      }

      implicit val argColor: Argument[Colorize] =
        new Argument[Colorize] {
          def defaultMetavar: String = "color"
          def read(str: String): ValidatedNel[String, Colorize] =
            str.toLowerCase match {
              case "none" => Validated.valid(Colorize.None)
              case "ansi" => Validated.valid(Colorize.Console)
              case "html" => Validated.valid(Colorize.HmtlFont)
              case other => Validated.invalidNel(s"unknown colorize: $other, expected: none, ansi or html")
            }
        }

      def pathGen(arg: String, help: String, ext: String): Opts[PathGen] = {
        val direct = toList(Opts.options[Path](arg, help = help))
          .map { paths => paths.foldMap(PathGen.Direct[IO, Path](_): PathGen) }

        unfoldDir match {
          case None => direct
          case Some(unfold) =>
            val select = hasExtension(ext)
            val child1 = toList(Opts.options[Path](arg + "_dir", help = s"all $help in directory"))
              .map { paths =>
                paths.foldMap(PathGen.ChildrenOfDir[IO, Path](_, select, false, unfold): PathGen)
              }
            val childMany = toList(Opts.options[Path](arg + "_all_subdir", help = s"all $help recursively in all directories"))
              .map { paths =>
                paths.foldMap(PathGen.ChildrenOfDir[IO, Path](_, select, true, unfold): PathGen)
              }

            (direct, child1, childMany).mapN { (a, b, c) =>
              (a :: b :: c :: Nil).combineAll
            }
        }
      }


      val srcs = pathGen("input", help = "input source files", ".bosatsu")
      val ifaces = pathGen("interface", help = "interface files", ".bosatsig")
      val includes = pathGen("include", help = "compiled packages to include files", ".bosatsu_package")

      val colorOpt = Opts.option[Colorize]("color", help = "colorize mode: none, ansi or html")
        .orElse(Opts(Colorize.Console))

      val mainP =
          MainIdentifier.opts(
            Opts.option[PackageName]("main", help = "main package to evaluate"),
            Opts.option[Path]("main_file", help = "file containing the main package to evaluate"))

      val testP =
          MainIdentifier.list(
            toList(Opts.options[PackageName]("test_package", help = "package for which to run tests")),
            toList(Opts.options[Path]("test_file", help = "file containing the package for which to run tests")))

      val outputPath = Opts.option[Path]("output", help = "output path")
      val interfaceOutputPath = Opts.option[Path]("interface_out", help = "interface output path")

      val packRoot =
        Opts.options[Path](
          "package_root",
          help = "for implicit package names, consider these paths as roots")

      val packSearch =
        resolvePath match {
          case None => Opts(None)
          case some@Some(_) =>
            Opts.flag("search", help = "if set, we search the package_roots for imports not explicitly given")
              .orFalse
              .map {
                case true => some
                case false => None
              }
        }

      val packRes: Opts[PackageResolver] =
        (packRoot.product(packSearch))
          .orNone
          .map {
            case None => PackageResolver.ExplicitOnly
            case Some((paths, search)) => PackageResolver.LocalRoots(paths, search)
          }

      // type-checking and writing protos should be explicit. search option isn't supported
      val noSearchRes: Opts[PackageResolver] =
        packRoot
          .orNone
          .map {
            case None => PackageResolver.ExplicitOnly
            case Some(paths) => PackageResolver.LocalRoots(paths, None)
          }

      val evalOpt = (srcs, mainP, includes, colorOpt, packRes)
        .mapN(Evaluate(_, _, _, _, _))
      val toJsonOpt = (srcs, includes, mainP, outputPath.orNone, colorOpt, packRes)
        .mapN(ToJson(_, _, _, _, _, _))
      val typeCheckOpt = (srcs, ifaces, outputPath, interfaceOutputPath.orNone, colorOpt, noSearchRes)
        .mapN(TypeCheck(_, _, _, _, _, _))
      val testOpt = (srcs, testP, includes, colorOpt, packRes)
        .mapN(RunTests(_, _, _, _, _))

      Opts.subcommand("eval", "evaluate an expression and print the output")(evalOpt)
        .orElse(Opts.subcommand("write-json", "evaluate a data expression into json")(toJsonOpt))
        .orElse(Opts.subcommand("type-check", "type check a set of packages")(typeCheckOpt))
        .orElse(Opts.subcommand("test", "test a set of bosatsu modules")(testOpt))
    }

    def command: Command[MainCommand] =
      Command("bosatsu", "a total and functional programming language")(opts)
  }

}