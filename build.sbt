inThisBuild(
  Seq(
    organization          := "com.example",
    version               := "0.1.0-SNAPSHOT",
    scalaVersion          := "3.3.0",
    watchTriggeredMessage := Watch.clearScreenOnTrigger,
    watchBeforeCommand    := Watch.clearScreen,
    //    resolvers += "jitpack" at "https://jitpack.io",
    libraryDependencies += "com.github.rssh" %% "shim-scala-async-dotty-cps-async" % "0.9.17",
    javacOptions ++= Seq("-source", "20", "-target", "20"),
    scalacOptions ++= Seq(
      "-encoding",
      "UTF-8",
      "-feature",
      "-unchecked",
      "-deprecation",
      "-Wunused:imports,privates,locals,implicits"
    )
  )
)

lazy val extraSourceDir = Compile / unmanagedSourceDirectories += (Compile / baseDirectory).value / "scala"

lazy val root = project
  .in(file("."))
  .aggregate(common, actor)

lazy val common = project
  .settings(extraSourceDir)

lazy val actor = project
  .dependsOn(common)
  .settings(extraSourceDir)
