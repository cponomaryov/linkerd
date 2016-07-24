package com.twitter.finagle.buoyant.http2

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.netty4.{BufAsByteBuf, ByteBufAsBuf}
import com.twitter.finagle.stats.{StatsReceiver, NullStatsReceiver}
import com.twitter.util.{Future, Promise, Return, Stopwatch, Throw}
import io.netty.buffer.CompositeByteBuf
import io.netty.handler.codec.http2._
import scala.collection.immutable.Queue
import java.util.concurrent.atomic.AtomicReference

private[http2] object Http2FrameDataStream {

  type DataReleaser = Int => Future[Unit]

  private trait State
  private object Open extends State
  private case class Closing(trailers: DataStream.Trailers) extends State
  private object Closed extends State

  val closedException =
    new IllegalStateException("stream closed")

  val noTrailersException =
    new IllegalStateException("closing state without trailers")

  def expectedOpenException(state: State) =
    new IllegalStateException(s"expected Open state; found ${state}")

  private case class StreamAccum(
    data: Option[DataStream.Data],
    trailers: Option[DataStream.Value]
  ) {
    require(data.nonEmpty || trailers.nonEmpty)
  }

  private class FrameData(frame: Http2DataFrame, releaser: DataReleaser)
    extends DataStream.Data {
    def buf = ByteBufAsBuf.Owned(frame.content /*.retain()*/ )
    def isEnd = frame.isEndStream
    private[this] val bytes = frame.content.readableBytes + frame.padding
    def release(): Future[Unit] = releaser(bytes)
  }
}

private[http2] class Http2FrameDataStream(
  fq: AsyncQueue[Http2StreamFrame],
  releaser: Http2FrameDataStream.DataReleaser,
  minAccumFrames: Int = 1,
  stats: StatsReceiver = NullStatsReceiver
) extends DataStream {

  import Http2FrameDataStream._

  private[this] val accumBytes = stats.stat("accum_bytes")
  private[this] val accumMicros = stats.stat("accum_us")
  private[this] val readQlens = stats.stat("read_qlen")
  private[this] val readMicros = stats.stat("read_us")

  private[this] val state: AtomicReference[State] =
    new AtomicReference(Open)

  private[this] val endP = new Promise[Unit]
  def onEnd: Future[Unit] = endP

  def fail(exn: Throwable): Future[Unit] = {
    fq.fail(exn, discard = true)
    endP.updateIfEmpty(Throw(exn))
    Future.Unit
  }

  def read(): Future[DataStream.Value] = {
    val start = Stopwatch.start()
    val f = state.get match {
      case Open =>
        val sz = fq.size
        readQlens.add(sz)
        if (sz < minAccumFrames) fq.poll().map(andDrainAccum)
        else Future.const(fq.drain()).map(accumStream)

      case Closed => Future.exception(closedException)

      case closing@Closing(trailers) =>
        if (state.compareAndSet(closing, Closed)) {
          endP.updateIfEmpty(Return.Unit)
          Future.value(trailers)
        } else Future.exception(closedException)
    }
    f.onSuccess(_ => readMicros.add(start().inMicroseconds))
    f
  }

  // Try to read as much data as is available so that it may be
  // chunked. This is done after poll() returns because it's likely
  // that multiple items may have entered the queue.
  private[this] val andDrainAccum: Http2StreamFrame => DataStream.Value = {
    case f: Http2HeadersFrame if f.isEndStream =>
      if (state.compareAndSet(Open, Closed)) DataStream.Trailers(Headers(f.headers))
      else throw expectedOpenException(state.get)

    case f: Http2DataFrame if f.isEndStream =>
      if (state.compareAndSet(Open, Closed)) toData(f)
      else throw expectedOpenException(state.get)

    case f: Http2DataFrame if fq.size < minAccumFrames => toData(f)

    case f: Http2DataFrame =>
      fq.drain() match {
        case Throw(_) => toData(f)
        case Return(q) => accumStream(f +: q)
      }

    case f =>
      throw new IllegalArgumentException("Unexpected frame: ${f.name}")
  }

  private def toData(f: Http2DataFrame): DataStream.Data =
    new FrameData(f, releaser)

  private val accumStream: Queue[Http2StreamFrame] => DataStream.Value = { frames =>
    require(frames.nonEmpty)
    val start = Stopwatch.start()

    var content: CompositeByteBuf = null
    var bytes = 0
    var eos = false
    var trailers: Option[DataStream.Trailers] = None

    var iter = frames.iterator
    while (iter.hasNext && !eos && trailers.isEmpty) {
      iter.next() match {
        case f: Http2DataFrame =>
          bytes += f.content.readableBytes + f.padding
          if (content == null) {
            content = f.content.alloc.compositeBuffer(frames.length)
          }
          content.addComponent(true /*advance writerIndex*/ , f.content)
          eos = f.isEndStream

        case f: Http2HeadersFrame =>
          trailers = Some(DataStream.Trailers(Headers(f.headers)))
      }
    }

    val data: Option[DataStream.Data] =
      if (content == null) None
      else {
        accumBytes.add(bytes)
        val data = new DataStream.Data {
          val buf = ByteBufAsBuf.Owned(content.retain())
          def isEnd = eos
          def release() = if (bytes > 0) releaser(bytes) else Future.Unit
        }
        Some(data)
      }

    val next = (data, trailers) match {
      case (Some(data), Some(trailers)) =>
        if (state.compareAndSet(Open, Closing(trailers))) data
        else throw expectedOpenException(state.get)
      case (Some(data), None) => data
      case (None, Some(trailers)) => trailers
      case (None, None) =>
        throw new IllegalStateException("No data or trailers available")
    }

    accumMicros.add(start().inMicroseconds)

    next
  }

}
