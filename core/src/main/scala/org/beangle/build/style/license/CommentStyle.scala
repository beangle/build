/*
 * Copyright © 2005, The Beangle Software.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.beangle.build.style.license

import org.beangle.build.util.EOF

import scala.util.matching.Regex

trait CommentStyle {
  def apply(license: String): String

  def extract(content: String): String
}

object CommentStyle {

  import Generator._

  val mappings = Map("scala" -> C, "java" -> C)

  lazy val C = new CommentStyleImpl(new Block("/*", " *", " */"),
    new RegexDetector(blockRegex("""/\*+""", "*", """\*/""")))

  private def blockRegex(begin: String, m: String, end: String): Regex =
    raw"""(?s)^($begin(?!\$m).*?$end(?:\n|\r|\r\n)+)(.*)""".r

  trait Generator {
    def apply(text: String): String
  }

  object Generator {

    final class Block(blockPrefix: String, linePrefix: String, blockSuffix: String) extends Generator {
      private val lines = new Lines(linePrefix)

      def apply(text: String): String =
        blockPrefix + EOF.LF + lines(text) + EOF.LF + blockSuffix
    }

    final class Lines(prefix: String) extends Generator {
      private val blankPrefix = prefix.trim.isEmpty

      override def apply(text: String): String = {
        def prepend(s: String): String =
          s match {
            case "" => if (blankPrefix) "" else prefix
            case line => prefix + " " + line
          }

        text.linesIterator.map(prepend).mkString(EOF.LF)
      }
    }

  }

  trait Detector {
    def extract(content: String): String
  }

  class RegexDetector(val regex: Regex) extends Detector {
    def extract(content: String): String = {
      regex.findFirstMatchIn(content) match {
        case Some(g) => g.group(1)
        case None => ""
      }
    }
  }

}

class CommentStyleImpl(generator: CommentStyle.Generator, detector: CommentStyle.Detector) extends CommentStyle {
  override def apply(license: String): String = {
    generator(license) + EOF.LF
  }

  override def extract(content: String): String = {
    detector.extract(content)
  }
}
