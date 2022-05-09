/*
 * Copyright 2015
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package influxdbreporter.core

import scala.collection.compat._
import com.codahale.metrics.Clock
import com.typesafe.scalalogging.LazyLogging
import influxdbreporter.core.collectors.MetricCollector
import influxdbreporter.core.metrics.{Metric, MetricByTag}
import influxdbreporter.core.metrics.Metric._
import influxdbreporter.core.writers.{Writer, WriterData}
import influxdbreporter.core.utils.ClockOpt.toClockOpt

import scala.concurrent.{ExecutionContext, Future}

abstract class BaseReporter[S](metricRegistry: MetricRegistry,
                               writer: Writer[S],
                               batcher: Batcher[S],
                               buffer: Option[WriterDataBuffer[S]],
                               clock: Clock)
                              (implicit ec: ExecutionContext)
  extends Reporter with LazyLogging {

  protected def reportCollectedMetrics(client: MetricClient[S]): Future[List[BatchReportingResult[S]]] = {
    val collectedMetricsFuture = synchronized {
      collectMetrics(metricRegistry.getMetricsMap)
    }
    for {
      collectedMetrics <- collectedMetricsFuture
      notYetSendMetrics = collectedMetrics ::: notYetSentMetricsFromBuffer
      _ <- Future(logger.debug("Partitioning metrics"))
      batches = batcher.partition(notYetSendMetrics)
      - <- Future(logger.debug("Sending metrics to influx"))
      reported <- reportMetricBatchesSequentially(batches) {
        client.sendData
      }
      - <- Future(logger.debug("Updating buffer with unsent metrics"))
      _ = updateNotSentMetricsBuffer(reported)
    } yield reported
  }

  private def notYetSentMetricsFromBuffer: List[WriterData[S]] = {
    buffer map (_.get()) getOrElse Nil
  }

  private def updateNotSentMetricsBuffer(reportResult: List[BatchReportingResult[S]]): Unit = {
    val (sent, notSent) = reportResult partition (_.reported)
    val notSentMetrics = notSent flatMap (_.batch)
    val sentMetrics = sent flatMap (_.batch)
    buffer.map(_.update(notSentMetrics, sentMetrics))
  }

  private def reportMetricBatchesSequentially[T](batches: IterableOnce[List[WriterData[T]]])
                                                (func: List[WriterData[T]] => Future[Boolean]): Future[List[BatchReportingResult[T]]] = {
    batches.iterator.foldLeft(Future.successful[List[BatchReportingResult[T]]](Nil)) {
      (acc, batch) => acc.flatMap { accList =>
        func(batch)
          .map(BatchReportingResult(batch, _) :: accList)
          .recover { case ex =>
            logger.error("Batch reporting error:", ex)
            BatchReportingResult(batch, reported = false) :: accList
          }
      }
    }
  }

  private def collectMetrics[M <: CodahaleMetric](metrics: Map[String, (Metric[M], MetricCollector[M])]): Future[List[WriterData[S]]] = {
    val timestamp = clock.getTimeInNanos
    for {
      _ <- Future(logger.debug("Started metrics collection"))
      collectedMetrics <- Future.sequence(metrics.toList.map {
        case (name, (metric, collector)) =>
          metric.popMetrics.map {
            _.flatMap {
              case MetricByTag(tags, m, timestampOpt) =>
                val result = collector.collect(writer, name, m, timestampOpt.getOrElse(timestamp), tags: _*)
                if(result.isEmpty) logger.warn(s"Metric $name was skipped because collector returns nothing")
                result
            }
          }
      }).map(listOfLists => listOfLists.flatten)
      _ <- Future(logger.debug("Finished metrics collection"))
    } yield collectedMetrics

  }

  protected case class BatchReportingResult[T](batch: List[WriterData[T]], reported: Boolean)

}