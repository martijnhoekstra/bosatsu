package org.bykn.bosatsu

import org.typelevel.paiges.Doc
import cats.parse.{LocationMap => CPLocationMap}

import cats.implicits._

import LocationMap.Colorize

/**
 * Build a cache of the rows and columns in a given
 * string. This is for showing error messages to users
 */
case class LocationMap(fromString: String) extends CPLocationMap(fromString) {

  private def lineRange(start: Int, end: Int): List[(Int, String)] =
    (start to end)
      .iterator
      .filter(_ >= 0)
      .map { r =>
        val liner = getLine(r).get // should never throw
        // lines are usually 1 offset labeled
        (r + 1, liner)
      }
      .toList


  def showContext(offset: Int, previousLines: Int, color: Colorize): Option[Doc] =
    toLineCol(offset)
      .map { case (r, c) =>
        val lines = lineRange(r - previousLines, r)

        val maxLine = lines.iterator.map(_._1).max
        val toLineStr = LocationMap.lineNumberToString(maxLine)

        // here is how much extra we need for the pointer
        val pointerPad = Doc.spaces(toLineStr(r).render(0).length)
        val lineDocs = lines.map { case (no, l) => toLineStr(no) + Doc.text(l) }
        val ctx = Doc.intercalate(Doc.hardLine, lineDocs)
        ctx + Doc.hardLine + pointerPad + LocationMap.pointerTo(c, color) + Doc.hardLine
      }

  def showRegion(region: Region, previousLines: Int, color: Colorize): Option[Doc] =
    (toLineCol(region.start), toLineCol(region.end - 1))
      .mapN { case ((l0, c0), (l1, c1)) =>
        val lines = lineRange(l0 - previousLines, l1)
        val maxLine = lines.iterator.map(_._1).max
        val toLineStr = LocationMap.lineNumberToString(maxLine)

        if (l0 == l1) {
          // same line
          // here is how much extra we need for the pointer
          val pointerPad = Doc.spaces(toLineStr(l0).render(0).length)
          val lineDocs = lines.map { case (no, l) => toLineStr(no) + Doc.text(l) }
          val ctx = Doc.intercalate(Doc.hardLine, lineDocs)
          ctx + Doc.hardLine + pointerPad + LocationMap.pointerRange(c0, c1 + 1, color) + Doc.hardLine
        }
        else {
          // we span multiple lines, show the start and the end:
          val newPrev = l1 - l0
          showContext(region.start, previousLines, color).get +
            Doc.hardLine + Doc.text("to:") + Doc.hardLine +
            showContext(region.end - 1, newPrev, color).get
        }
      }

}

object LocationMap {
  sealed trait Colorize {
    def red(d: Doc): Doc
    def green(d: Doc): Doc
  }

  object Colorize {
    object None extends Colorize {
      def red(d: Doc) = d
      def green(d: Doc) = d
    }

    object Console extends Colorize {
      def red(d: Doc) =
        Doc.zeroWidth(scala.Console.RED) + d.unzero + Doc.zeroWidth(scala.Console.RESET)

      def green(d: Doc) =
        Doc.zeroWidth(scala.Console.GREEN) + d.unzero + Doc.zeroWidth(scala.Console.RESET)
    }

    object HmtlFont extends Colorize {
      def red(d: Doc) =
        Doc.zeroWidth("<font color=\"red\">") + d.unzero + Doc.zeroWidth("</font>")

      def green(d: Doc) =
        Doc.zeroWidth("<font color=\"green\">") + d.unzero + Doc.zeroWidth("</font>")
    }
  }

  /**
   * Provide a string that points with a carat to a given column
   * with 0 based indexing:
   * e.g. pointerTo(2) == "  ^"
   */
  def pointerTo(column: Int, color: Colorize): Doc = {
    val col = Doc.spaces(column)
    val pointer = Doc.char('^')
    col + color.red(pointer)
  }

  def pointerRange(start: Int, exEnd: Int, color: Colorize): Doc = {
    val width = exEnd - start
    if (width <= 0) Doc.empty
    else {
      val col = Doc.spaces(start)
      val pointer = Doc.char('^') * width
      col + color.red(pointer)
    }
  }

  def charsLineNumber(i: Int): Int = {
    require(i >= 0, s"expect line > 0, found $i")
    def go(i: Int, acc: Int): Int =
      if (i < 10) acc
      else go(i / 10, acc + 1)

    go(i, 1)
  }

  def lineNumberToString(maxLine: Int): Int => Doc = {
    val padding = LocationMap.charsLineNumber(maxLine)

    { (i: Int) =>
      val istr = i.toString
      val pad = padding - istr.length
      Doc.spaces(pad) + (Doc.text(istr) + Doc.char('|'))
    }
  }
}
