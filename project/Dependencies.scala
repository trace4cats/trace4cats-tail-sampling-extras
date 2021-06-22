import sbt._

object Dependencies {
  object Versions {
    val scala212 = "2.12.14"
    val scala213 = "2.13.6"

    val trace4cats = "0.12.0-RC1+156-7ea07b63"

    val embeddedRedis = "0.7.3"
    val redis4cats = "1.0.0-RC3"
    val scaffeine = "5.0.0"

    val kindProjector = "0.13.0"
    val betterMonadicFor = "0.3.1"
  }

  lazy val trace4catsTailSampling = "io.janstenpickle" %% "trace4cats-tail-sampling" % Versions.trace4cats
  lazy val trace4catsTestkit = "io.janstenpickle"      %% "trace4cats-testkit"       % Versions.trace4cats

  lazy val embeddedRedis = "it.ozimov"            % "embedded-redis"      % Versions.embeddedRedis
  lazy val redis4cats = "dev.profunktor"         %% "redis4cats-effects"  % Versions.redis4cats
  lazy val redis4catsLog4cats = "dev.profunktor" %% "redis4cats-log4cats" % Versions.redis4cats
  lazy val scaffeine = "com.github.blemale"      %% "scaffeine"           % Versions.scaffeine

  lazy val kindProjector = ("org.typelevel" % "kind-projector"     % Versions.kindProjector).cross(CrossVersion.full)
  lazy val betterMonadicFor = "com.olegpy" %% "better-monadic-for" % Versions.betterMonadicFor
}
