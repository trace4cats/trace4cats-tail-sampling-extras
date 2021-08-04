package io.janstenpickle.trace4cats.sampling.tail.cache

import cats.effect.kernel.Sync
import cats.syntax.functor._
import io.janstenpickle.trace4cats.model.{SampleDecision, TraceId}
import io.janstenpickle.trace4cats.sampling.tail.SampleDecisionStore
import io.janstenpickle.trace4cats.sampling.tail.caffeine.CaffeineCache

import scala.concurrent.duration._

object LocalCacheSampleDecisionStore {
  def apply[F[_]: Sync](ttl: FiniteDuration = 5.minutes, maximumSize: Option[Long] = None): F[SampleDecisionStore[F]] =
    CaffeineCache[F, TraceId, SampleDecision](ttl, maximumSize)
      .map { cache =>
        new SampleDecisionStore[F] {
          override def getDecision(traceId: TraceId): F[Option[SampleDecision]] =
            cache.getIfPresent(traceId)

          override def storeDecision(traceId: TraceId, sampleDecision: SampleDecision): F[Unit] =
            cache.put(traceId, sampleDecision)

          override def batch(traceIds: Set[TraceId]): F[Map[TraceId, SampleDecision]] =
            cache.getAllPresent(traceIds)

          override def storeDecisions(decisions: Map[TraceId, SampleDecision]): F[Unit] =
            cache.putAll(decisions)
        }
      }
}
