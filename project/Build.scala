import sbt.Keys._
import sbt._

object BuildSettings {
  val scalaTest = "org.scalatest" %% "scalatest" % "3.2.20"

  private val stubJavadocSettings = Seq(
    Compile / doc / sources := Nil,
    Compile / packageDoc := {
      val out = (Compile / packageDoc / artifactPath).value
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
      val mappings = (dir ** "*").get pair Path.rebase(dir, "")
      IO.jar(mappings, out, manifest, Some(0L))
      out
    }
  )

  val commonSettings = stubJavadocSettings ++ Seq(
    organizationName := "The Beangle Software",
    startYear := Some(2005),
    licenses += ("GNU General Public License version 3", url("http://www.gnu.org/licenses/lgpl-3.0.txt")),
    libraryDependencies ++= Seq(scalaTest % Test),
    crossPaths := true,

    publishMavenStyle := true,
    publishConfiguration := publishConfiguration.value.withOverwrite(true),
    publishM2Configuration := publishM2Configuration.value.withOverwrite(true),
    publishLocalConfiguration := publishLocalConfiguration.value.withOverwrite(true),

    versionScheme := Some("early-semver"),
    pomIncludeRepository := { _ => false }, // Remove all additional repository other than Maven Central from POM
    credentials += Credentials(Path.userHome / ".sbt" / "sonatype_central_credentials"),
    publishTo := localStaging.value
  )
}
