import sbt.*
import sbt.Keys.*
import com.typesafe.tools.mima.plugin.MimaPlugin.autoImport.mimaPreviousArtifacts
import sbtversionpolicy.SbtVersionPolicyPlugin.autoImport.{Compatibility, versionPolicyIntention}

object BuildSettings {
  val scalaTest = "org.scalatest" %% "scalatest" % "3.2.20"

  private val stubJavadocSettings = Seq(
    Compile / doc / sources := Nil,
    Compile / packageDoc := Def.uncached {
      val converter = fileConverter.value
      val outFile = (Compile / target).value / s"${name.value}-${version.value}-javadoc.jar"
      val dir = (Compile / target).value / "stub-javadoc"
      IO.createDirectory(dir)
      val docUrl = homepage.value.map(_.toString).getOrElse("https://beangle.github.io/")
      IO.write(
        dir / "README.md",
        s"""# No bundled API documentation
           |
           |Documentation: $docUrl
           |Source code: `-sources.jar`
           |""".stripMargin
      )
      val manifest = new java.util.jar.Manifest()
      manifest.getMainAttributes.put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0")
      val mappings = (dir ** "*").get() pair Path.rebase(dir, "")
      IO.jar(mappings, outFile, manifest, Some(0L))
      converter.toVirtualFile(outFile.toPath)
    }
  )

  val commonSettings = stubJavadocSettings ++ Seq(
    organizationName := "The Beangle Software",
    startYear := Some(2005),
    licenses += sbt.librarymanagement.License("GPL-3.0", uri("http://www.gnu.org/licenses/gpl-3.0.txt")),
    libraryDependencies ++= Seq(scalaTest % Test),
    crossPaths := true,

    publishMavenStyle := true,
    publishConfiguration := Def.uncached(publishConfiguration.value.withOverwrite(true)),
    publishM2Configuration := Def.uncached(publishM2Configuration.value.withOverwrite(true)),
    publishLocalConfiguration := Def.uncached(publishLocalConfiguration.value.withOverwrite(true)),

    versionScheme := Some("early-semver"),
    versionPolicyIntention := Compatibility.None,
    mimaPreviousArtifacts := Set.empty,
    pomIncludeRepository := { _ => false },
    sbtPluginPublishLegacyMavenStyle := false,
    publishTo := {
      val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
      if version.value.endsWith("-SNAPSHOT") then Some("central-snapshots" at centralSnapshots)
      else localStaging.value
    },
    credentials += Credentials(Path.userHome / ".sbt" / "sonatype_central_credentials"),
    addSbtPlugin("ch.epfl.scala" % "sbt-version-policy" % "3.3.0"),
    addSbtPlugin("com.github.sbt" % "sbt-pgp" % "2.3.1")
  )
}
