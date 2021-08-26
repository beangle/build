import BuildSettings._
import sbt._

ThisBuild / version := "0.0.1"
ThisBuild / description := "Beangle Build Tools."
ThisBuild / organization := "org.beangle.build"
ThisBuild / homepage := Some(url("https://github.com/beangle/build"))
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/beangle/build"),
    "scm:git@github.com:beangle/build.git"
  )
)
ThisBuild / developers := List(
  Developer(
    id = "duantihua",
    name = "Duan Tihua",
    email = "duantihua@163.com",
    url = url("https://github.com/duantihua")
  )
)

ThisBuild / credentials += Credentials(Path.userHome / ".sbt" / "sonatype_credentials")
ThisBuild / publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value) Some("snapshots" at nexus + "content/repositories/snapshots")
  else Some("releases" at nexus + "service/local/staging/deploy/maven2")
}

lazy val root = (project in file("."))
  .enablePlugins(SbtPlugin)
  .settings(
    name := "sbt-build",
    commonSettings
  )
