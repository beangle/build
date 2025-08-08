import BuildSettings.*
import sbt.*

ThisBuild / version := "0.0.18-SNAPSHOT"
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

val apache_commons_compress = "org.apache.commons" % "commons-compress" % "1.28.0"

lazy val root = (project in file("."))
  .enablePlugins(SbtPlugin)
  .settings(
    name := "sbt-beangle-build",
    libraryDependencies ++= Seq(apache_commons_compress),
    commonSettings
  )
