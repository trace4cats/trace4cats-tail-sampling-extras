package io.janstenpickle.trace4cats.sampling.tail.caffeine

import cats.effect.kernel.Sync
import cats.syntax.functor._
import com.github.benmanes.caffeine.cache.{Caffeine => JCaffeine}

import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._
import scala.jdk.DurationConverters._

trait CaffeineCache[F[_], K, V] {
  def put(key: K, value: V): F[Unit]
  def putAll(map: Map[K, V]): F[Unit]
  def getIfPresent(key: K): F[Option[V]]
  def getAllPresent(keys: Iterable[K]): F[Map[K, V]]
}

object CaffeineCache {
  def apply[F[_], K, V](expiresAfter: FiniteDuration, maxSize: Option[Long])(implicit
    F: Sync[F]
  ): F[CaffeineCache[F, K, V]] =
    F.delay {
      val builder = JCaffeine
        .newBuilder()
        .expireAfterAccess(expiresAfter.toJava)
      maxSize.fold(builder)(builder.maximumSize).build[K, V]()
    }.map(underlying =>
      new CaffeineCache[F, K, V] {
        def put(key: K, value: V): F[Unit] = F.delay(underlying.put(key, value))
        def putAll(map: Map[K, V]): F[Unit] = F.delay(underlying.putAll(map.asJava))
        def getIfPresent(key: K): F[Option[V]] = F.delay(Option(underlying.getIfPresent(key)))
        def getAllPresent(keys: Iterable[K]): F[Map[K, V]] =
          F.delay(underlying.getAllPresent(keys.asJava).asScala.toMap)
      }
    )
}
