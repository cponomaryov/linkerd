package io.buoyant.router.h2

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.buoyant.h2.Frame.Trailers
import com.twitter.finagle.{Service, ServiceFactory, Stack, param}
import com.twitter.finagle.buoyant.h2.{Frame, Method, Request, Response, Status, Stream}
import com.twitter.finagle.stats.InMemoryStatsReceiver
import com.twitter.io.Buf
import com.twitter.util.Future
import io.buoyant.test.{Awaits, FunSuite}

class StreamStatsFilterTest extends FunSuite with Awaits {
  val successCounters = Seq(
    Seq("response", "stream", "stream_successes"),
    Seq("request", "stream", "stream_successes"),
    Seq("stream", "stream_successes"),
    Seq("successes")
  )
  val failureCounters = Seq(
    Seq("response", "stream", "stream_failures"),
    Seq("request", "stream", "stream_failures"),
    Seq("stream", "stream_failures"),
    Seq("failures")
  )
  val requestCounter = Seq("requests")
  val allCounters = successCounters ++ failureCounters :+ requestCounter
  val latencyStats = Seq(
    Seq("response", "stream", "stream_duration_ms"),
    Seq("request", "stream", "stream_duration_ms"),
    Seq("stream", "total_latency_ms"),
    Seq("request_latency_ms")
  )

  private[this] def setup(response: Request => Future[Response]) = {

    val stats = new InMemoryStatsReceiver()
    val svc = Service.mk[Request, Response] { response(_) }
    val stk = StreamStatsFilter.module.toStack(
      Stack.Leaf(StreamStatsFilter.role, ServiceFactory.const(svc))
    )
    val service = await(stk.make(Stack.Params.empty + param.Stats(stats))())

    (stats, service)
  }

  test("increments success counters on success") {

    val (stats, service) = setup { _ =>
      Future.value(Response(Status.Ok, Stream.const("aaaaaaaaaaaa")))
    }

    // all counters should be empty before firing request
    withClue("before request") {
      for {counter <- allCounters}
        withClue(s"stat: $counter") { assert(!stats.counters.isDefinedAt(counter)) }
    }

    val req = Request("http", Method.Get, "hihost", "/", Stream.empty())
    var rsp = await(service(req))
    var stream = rsp.stream
    var frame = await(stream.read())
    frame.release()

    withClue("after first response") {
      for { counter <- successCounters :+ requestCounter }
        withClue(s"stat: $counter") { assert(stats.counters(counter) == 1) }
    }

    rsp = await(service(req))
    stream = rsp.stream
    frame = await(stream.read())
    frame.release

    withClue("after second response") {
      for { counter <- successCounters :+ requestCounter }
        withClue(s"stat: $counter") { assert(stats.counters(counter) == 2) }
    }
  }

  test("does not increment failure counters after successes") {
    val (stats, service) = setup { _ =>
      val stream = Stream.const("aaaaaaaaa")
      Future.value(Response(Status.Ok, stream))
    }

    val req = Request("http", Method.Get, "hihost", "/", Stream.empty())
    var rsp = await(service(req))
    var stream = rsp.stream
    var frame = await(stream.read())

    withClue("after first response") {
      for { counter <- failureCounters }
        withClue(s"stat: $counter") { assert(!stats.counters.isDefinedAt(counter)) }
    }

    rsp = await(service(req))
    stream = rsp.stream
    frame = await(stream.read())
    frame.release

    withClue("after second response") {
      for { counter <- failureCounters }
        withClue(s"stat: $counter") { assert(!stats.counters.isDefinedAt(counter)) }
    }
  }

  object Thrown extends Throwable

  test("increments correct failure counters after failure") {
    val (stats, service) = setup { _ =>
      Future.exception(Thrown)
    }
    val req = Request("http", Method.Get, "hihost", "/", Stream.empty())
    val expectedCounters = Seq(
      requestCounter,
      Seq("failures"),
      Seq("request", "stream", "stream_successes"))

    assertThrows[Throwable] { await(service(req)) }
    withClue("after first failure") {
      for { counter <- expectedCounters }
        assert(stats.counters(counter) == 1)
      // counters that should not be incremented on this failure
      for { counter <- failureCounters if !expectedCounters.contains(counter) }
        withClue(s"stat: $counter") { assert(!stats.counters.isDefinedAt(counter)) }

    }

    assertThrows[Throwable] { await(service(req)) }
    withClue("after second failure") {
      for { counter <- expectedCounters }
        withClue(s"stat: $counter") { assert(stats.counters(counter) == 2) }

      // counters that should not be incremented on this failure
      for { counter <- failureCounters if !expectedCounters.contains(counter) }
        withClue(s"stat: $counter") { assert(!stats.counters.isDefinedAt(counter)) }
    }
  }

  test("stats are defined after success") {
    val (stats, service) = setup { _ =>
      Future.value(Response(Status.Ok, Stream.const("aaaaaaaaa")))
    }
    withClue("before request") {
      // stats undefined before request
      for {stat <- latencyStats}
        withClue(s"stat: $stat") { assert(!stats.stats.isDefinedAt(stat)) }
    }

    val req = Request("http", Method.Get, "hihost", "/", Stream.empty())
    val rsp = await(service(req))
    val stream = rsp.stream
    val frame = await(stream.read())
    frame.release()
    withClue("after success") {
      for {stat <- latencyStats}
        withClue(s"stat: $stat") { assert(stats.stats.isDefinedAt(stat)) }
    }
  }

  test("stats are defined after failure") {
    val (stats, service) = setup { _ =>
      Future.exception(Thrown)
    }
    withClue("before request") {
      // stats undefined before request
      for {stat <- latencyStats}
        withClue(s"stat: $stat") {assert(!stats.stats.isDefinedAt(stat)) }
    }

    val req = Request("http", Method.Get, "hihost", "/", Stream.empty())
    assertThrows[Throwable] { await(service(req)) }
    withClue("after failure") {
      for { stat <- latencyStats.filter(_ != Seq("response", "stream", "stream_duration_ms")) }
        withClue(s"stat: $stat") { assert(stats.stats.isDefinedAt(stat)) }
    }
  }
}
