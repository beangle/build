/*
 * Copyright (C) 2005, The Beangle Software.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.beangle.build.sbt

import sbt.*

object Bas {
  val JulToSlf4j = "org.slf4j" % "jul-to-slf4j" % "2.0.18" % "test"

  val Engine = "org.beangle.bas" % "beangle-bas-engine" % "0.14.0" % "test"

  val TomcatCore = ("org.apache.tomcat.embed" % "tomcat-embed-core" % "11.0.26" % "test").exclude("org.apache.tomcat", "tomcat-annotations-api")
  val TomcatWebSocket = ("org.apache.tomcat.embed" % "tomcat-embed-websocket" % "11.0.26" % "test").exclude("org.apache.tomcat", "tomcat-annotations-api")

  val Undertow = "io.undertow.ee" % "undertow-servlet" % "2.0.3.Final" % "test"
}
