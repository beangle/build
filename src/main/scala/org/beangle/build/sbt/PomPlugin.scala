/*
 * Copyright (C) 2005, The Beangle Software.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.beangle.build.sbt

import sbt.*
import sbt.Keys.*

/** 打包时内嵌 Maven 元信息，与 Maven `mvn package` 的行为对齐。
 *
 * 在 jar/war 中生成 `META-INF/maven/<groupId>/<artifactId>/pom.xml` 与
 * `pom.properties`（内容复用 `makePom`，与发布时一致，同样应用 `pomPostProcess`）；
 * war 项目经 `Compile / packageBin / mappings` 自动落入 `WEB-INF/classes`。
 */
object PomPlugin extends sbt.AutoPlugin {

  override def trigger = allRequirements

  override def projectSettings: Seq[Setting[?]] = Seq(
    Compile / packageBin / mappings ++= Def.uncached {
      val converter = fileConverter.value
      val pom = converter.toPath((Compile / makePom).value).toFile
      val id = projectID.value
      val dir = s"META-INF/maven/${id.organization}/${id.name}"
      val properties = (Compile / target).value / "maven-metadata" / "pom.properties"
      IO.write(properties, s"groupId=${id.organization}\nartifactId=${id.name}\nversion=${id.revision}\n")
      Seq(
        converter.toVirtualFile(pom.toPath) -> s"$dir/pom.xml",
        converter.toVirtualFile(properties.toPath) -> s"$dir/pom.properties"
      )
    }
  )
}
