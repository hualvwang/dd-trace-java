package datadog.trace.api.http

import datadog.trace.api.function.BiFunction
import datadog.trace.api.function.Supplier
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.InstrumentationGateway
import datadog.trace.api.gateway.RequestContext
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.test.util.DDSpecification

import static datadog.trace.api.gateway.Events.EVENTS

class StoredBodyFactoriesTest extends DDSpecification {
  AgentTracer.TracerAPI originalTracer

  AgentTracer.TracerAPI tracerAPI = Mock()
  AgentSpan agentSpan

  RequestContext<Object> requestContext = Mock(RequestContext) {
    getData() >> it
  }
  InstrumentationGateway ig = Mock()

  def setup() {
    originalTracer = AgentTracer.provider
    AgentTracer.provider = tracerAPI
    _ * tracerAPI.activeSpan() >> { agentSpan }
    _ * tracerAPI.instrumentationGateway() >> ig
  }

  def cleanup() {
    AgentTracer.provider = originalTracer
  }

  void 'no active span'() {
    expect:
    StoredBodyFactories.maybeCreateForByte(null, null) == null
    StoredBodyFactories.maybeCreateForChar(null) == null
    StoredBodyFactories.maybeDeliverBodyInOneGo('').is(Flow.ResultFlow.empty())
  }

  void 'no active context'() {
    agentSpan = Mock()

    when:
    StoredBodyFactories.maybeCreateForByte(null, null) == null
    StoredBodyFactories.maybeCreateForChar(null) == null
    StoredBodyFactories.maybeDeliverBodyInOneGo('').is(Flow.ResultFlow.empty())

    then:
    3 * agentSpan.requestContext >> null
  }

  void 'no IG callbacks'() {
    agentSpan = Mock()

    when:
    StoredBodyFactories.maybeCreateForByte(null, null) == null
    StoredBodyFactories.maybeCreateForChar(null) == null
    StoredBodyFactories.maybeDeliverBodyInOneGo('').is(Flow.ResultFlow.empty())

    then:
    3 * agentSpan.requestContext >> requestContext
  }

  void 'everything needed provided'() {
    agentSpan = Mock()
    def mockRequestBodyStart = Mock(BiFunction)
    def mockRequestBodyDone = Mock(BiFunction)
    StoredBodySupplier bodySupplier1, bodySupplier2
    Flow mockFlow = Mock()

    when:
    StoredBodyFactories.maybeCreateForByte(null, null) != null
    StoredBodyFactories.maybeCreateForChar(null) != null

    then:
    2 * agentSpan.requestContext >> requestContext
    2 * ig.getCallback(EVENTS.requestBodyStart()) >> Mock(BiFunction)
    2 * ig.getCallback(EVENTS.requestBodyDone()) >> Mock(BiFunction)

    when:
    Flow f = StoredBodyFactories.maybeDeliverBodyInOneGo({ 'body' } as Supplier<CharSequence>)

    then:
    1 * agentSpan.requestContext >> requestContext
    1 * ig.getCallback(EVENTS.requestBodyStart()) >> mockRequestBodyStart
    1 * ig.getCallback(EVENTS.requestBodyDone()) >> mockRequestBodyDone
    1 * mockRequestBodyStart.apply(requestContext, _ as StoredBodySupplier) >> {
      bodySupplier1 = it[1]
      null
    }
    1 * mockRequestBodyDone.apply(requestContext, _ as StoredBodySupplier) >> {
      bodySupplier2 = it[1]
      mockFlow
    }
    bodySupplier1.is(bodySupplier2)
    bodySupplier2.get() == 'body'
    f.is(mockFlow)
  }

  void 'everything needed provided delivery in one go string variant'() {
    agentSpan = Mock()
    def mockRequestBodyStart = Mock(BiFunction)
    def mockRequestBodyDone = Mock(BiFunction)
    StoredBodySupplier bodySupplier
    Flow mockFlow = Mock()

    when:
    Flow f = StoredBodyFactories.maybeDeliverBodyInOneGo('body')

    then:
    1 * agentSpan.requestContext >> requestContext
    1 * ig.getCallback(EVENTS.requestBodyStart()) >> mockRequestBodyStart
    1 * ig.getCallback(EVENTS.requestBodyDone()) >> mockRequestBodyDone
    1 * mockRequestBodyDone.apply(requestContext, _ as StoredBodySupplier) >> {
      bodySupplier = it[1]
      mockFlow
    }
    bodySupplier.get() == 'body'
    f.is(mockFlow)
  }

  void 'with correct content length'() {
    agentSpan = Mock()

    when:
    StoredBodyFactories.maybeCreateForByte(null, '1') != null
    StoredBodyFactories.maybeCreateForChar('1') != null

    then:
    2 * agentSpan.requestContext >> requestContext
    2 * ig.getCallback(EVENTS.requestBodyStart()) >> Mock(BiFunction)
    2 * ig.getCallback(EVENTS.requestBodyDone()) >> Mock(BiFunction)
  }

  void 'with correct content length int version'() {
    agentSpan = Mock()

    when:
    StoredBodyFactories.maybeCreateForByte(null, 1) != null
    StoredBodyFactories.maybeCreateForChar(1) != null

    then:
    2 * agentSpan.requestContext >> requestContext
    2 * ig.getCallback(EVENTS.requestBodyStart()) >> Mock(BiFunction)
    2 * ig.getCallback(EVENTS.requestBodyDone()) >> Mock(BiFunction)
  }

  void 'with bad content length'() {
    agentSpan = Mock()

    when:
    StoredBodyFactories.maybeCreateForByte(null, 'foo') != null
    StoredBodyFactories.maybeCreateForChar('foo') != null

    then:
    2 * agentSpan.requestContext >> requestContext
    2 * ig.getCallback(EVENTS.requestBodyStart()) >> Mock(BiFunction)
    2 * ig.getCallback(EVENTS.requestBodyDone()) >> Mock(BiFunction)
  }
}
