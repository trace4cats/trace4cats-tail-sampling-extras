package io.janstenpickle.trace4cats.sampling.tail.redis

import cats.data.NonEmptyList
import cats.effect.kernel.{Async, Resource, Sync}
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.parallel._
import cats.{Applicative, Parallel}
import dev.profunktor.redis4cats.codecs.Codecs
import dev.profunktor.redis4cats.codecs.splits.SplitEpi
import dev.profunktor.redis4cats.data.RedisCodec
import dev.profunktor.redis4cats.log4cats._
import dev.profunktor.redis4cats.{Redis, RedisCommands}
import io.janstenpickle.trace4cats.model.{SampleDecision, TraceId}
import io.janstenpickle.trace4cats.sampling.tail.SampleDecisionStore
import io.janstenpickle.trace4cats.sampling.tail.caffeine.CaffeineCache
import io.lettuce.core.ClientOptions
import org.typelevel.log4cats.Logger

import scala.concurrent.duration.FiniteDuration

object RedisSampleDecisionStore {
  private val traceIdSplit: SplitEpi[Array[Byte], (Short, TraceId)] = SplitEpi(
    ba => (0, TraceId(ba.drop(1)).getOrElse(TraceId.invalid)),
    { case (prefix, traceId) =>
      traceId.value.+:(prefix.byteValue)
    }
  )
  private val booleanSplit: SplitEpi[Array[Byte], SampleDecision] = SplitEpi(
    {
      case Array(1) => SampleDecision.Drop
      case _ => SampleDecision.Include
    },
    {
      case SampleDecision.Drop => Array(1)
      case SampleDecision.Include => Array(0)
    }
  )

  private val codec: RedisCodec[(Short, TraceId), SampleDecision] =
    Codecs.derive(RedisCodec.Bytes, traceIdSplit, booleanSplit)

  def apply[F[_]: Sync: Parallel](
    cmd: RedisCommands[F, (Short, TraceId), SampleDecision],
    keyPrefix: Short,
    ttl: FiniteDuration,
    maximumLocalCacheSize: Option[Long]
  ): F[SampleDecisionStore[F]] =
    CaffeineCache[F, TraceId, SampleDecision](ttl, maximumLocalCacheSize)
      .map { cache =>
        def cacheDecision(traceId: TraceId, decision: F[Option[SampleDecision]]) =
          decision.flatTap {
            case Some(value) => cache.put(traceId, value)
            case None => Applicative[F].unit
          }

        new SampleDecisionStore[F] {
          override def getDecision(traceId: TraceId): F[Option[SampleDecision]] =
            cache.getIfPresent(traceId).flatMap {
              case v @ Some(_) => Applicative[F].pure(v)
              case None => cacheDecision(traceId, cmd.get(keyPrefix -> traceId))
            }

          override def batch(traceIds: Set[TraceId]): F[Map[TraceId, SampleDecision]] = if (traceIds.isEmpty)
            Applicative[F].pure(Map.empty)
          else {
            def getRemote(remainder: Set[TraceId]) = for {
              remote <- cmd.mGet(remainder.map(keyPrefix -> _))
              remoteMapped = remote.map { case ((_, traceId), decision) => traceId -> decision }
              _ <- cache.putAll(remoteMapped)
            } yield remoteMapped

            for {
              local <- cache.getAllPresent(traceIds)
              remainder = traceIds.diff(local.keySet)
              remote <- if (remainder.isEmpty) Applicative[F].pure(Map.empty) else getRemote(remainder)
            } yield local ++ remote
          }

          override def storeDecision(traceId: TraceId, sampleDecision: SampleDecision): F[Unit] =
            cmd.setEx(keyPrefix -> traceId, sampleDecision, ttl) >> cache.put(traceId, sampleDecision)

          override def storeDecisions(decisions: Map[TraceId, SampleDecision]): F[Unit] = if (decisions.isEmpty)
            Applicative[F].unit
          else
            decisions.toList.parTraverse_ { case (traceId, decision) =>
              cmd.setEx(keyPrefix -> traceId, decision, ttl)
            } >> cache.putAll(decisions)
        }

      }

  private def redisUrl(host: String, port: Int): String = s"redis://$host:$port"

  def apply[F[_]: Async: Parallel: Logger](
    host: String,
    port: Int,
    keyPrefix: Short,
    ttl: FiniteDuration,
    maximumLocalCacheSize: Option[Long],
    modifyOptions: ClientOptions => ClientOptions = identity
  ): Resource[F, SampleDecisionStore[F]] =
    for {
      opts <- Resource.eval(Sync[F].delay(modifyOptions(ClientOptions.create())))
      cmd <- Redis[F].withOptions(redisUrl(host, port), opts, codec)
      sampler <- Resource.eval(apply[F](cmd, keyPrefix, ttl, maximumLocalCacheSize))
    } yield sampler

  def cluster[F[_]: Async: Parallel: Logger](
    servers: NonEmptyList[(String, Int)],
    keyPrefix: Short,
    ttl: FiniteDuration,
    maximumLocalCacheSize: Option[Long],
  ): Resource[F, SampleDecisionStore[F]] =
    for {
      cmd <- Redis[F].cluster(codec, servers.map((redisUrl _).tupled).toList: _*)()
      sampler <- Resource.eval(apply[F](cmd, keyPrefix, ttl, maximumLocalCacheSize))
    } yield sampler
}
