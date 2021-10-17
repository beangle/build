/*
 * Beangle, Agile Development Scaffold and Toolkits.
 *
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

package org.beangle.build.boot

import java.io.File

object Dependency {

  def m2Path(m2Root: String, group: String, artifactName: String, version: String, packaging: String = ".jar"): String = {
    val m2Base = new File(if (m2Root.startsWith("file:")) m2Root.substring("file:".length) else m2Root).getCanonicalPath
    if (File.separatorChar == '/') {
      s"${m2Base}/${group.replace('.', '/')}/${artifactName}/${version}/${artifactName}-${version}${packaging}"
    } else {
      s"${m2Base}\\${group.replace('.', '\\')}\\${artifactName}\\${version}\\${artifactName}-${version}${packaging}"
    }
  }

  def m2DiffPath(m2Root: String, group: String, artifactName: String, oldVersion: String, newVersion: String, packaging: String = ".jar"): String = {
    val m2Base = new File(if (m2Root.startsWith("file:")) m2Root.substring("file:".length) else m2Root).getCanonicalPath
    if (File.separatorChar == '/') {
      s"${m2Base}/${group.replace('.', '/')}/${artifactName}/${newVersion}/${artifactName}-${oldVersion}_${newVersion}${packaging}.diff"
    } else {
      s"${m2Base}/${group.replace('.', '\\')}/${artifactName}\\${newVersion}\\${artifactName}-${oldVersion}_${newVersion}${packaging}.diff"
    }
  }

}

class Dependency(val groupId: String, val artifactId: String) {

  def matches(other: Dependency): Boolean = {
    (groupId == "*" || groupId == other.groupId) && (artifactId == "*" || artifactId == other.artifactId)
  }
}
