import BuildSettings.*
import sbt.*

version := "0.1.0-SNAPSHOT"
description := "Beangle Build Tools."
organization := "org.beangle.build"
homepage := Some(uri("https://github.com/beangle/build"))
scmInfo := Some(
  ScmInfo(
    uri("https://github.com/beangle/build"),
    "scm:git@github.com:beangle/build.git"
  )
)
developers := List(
  Developer(
    id = "duantihua",
    name = "Duan Tihua",
    email = "duantihua@163.com",
    url = uri("https://github.com/duantihua")
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
