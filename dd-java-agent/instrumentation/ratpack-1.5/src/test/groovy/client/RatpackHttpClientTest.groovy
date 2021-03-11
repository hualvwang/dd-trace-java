package client

import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.instrumentation.netty41.client.NettyHttpClientDecorator
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import ratpack.exec.ExecResult
import ratpack.http.client.HttpClient
import ratpack.test.exec.ExecHarness
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Timeout

import java.time.Duration

@Timeout(5)
class RatpackHttpClientTest extends HttpClientTest {

  @AutoCleanup
  @Shared
  ExecHarness exec = ExecHarness.harness()

  @Shared
  def sslContext = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build()

  @Shared
  def client = HttpClient.of {
    it.readTimeout(Duration.ofSeconds(2))
    // Connect timeout added in 1.5
  }

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    ExecResult<Integer> result = exec.yield {
      def resp = client.request(uri) { spec ->
        spec.connectTimeout(Duration.ofSeconds(2))
        spec.sslContext(sslContext)
        spec.method(method)
        spec.headers { headersSpec ->
          headers.entrySet().each {
            headersSpec.add(it.key, it.value)
          }
        }
      }
      return resp.map {
        callback?.call()
        it.status.code
      }
    }
    return result.value
  }

  @Override
  CharSequence component() {
    return NettyHttpClientDecorator.DECORATE.component()
  }

  @Override
  String expectedOperationName() {
    return "netty.client.request"
  }

  @Override
  boolean testRedirects() {
    false
  }

  @Override
  boolean testConnectionFailure() {
    false
  }

  @Override
  boolean testRemoteConnection() {
    return false
  }

  @Override
  boolean testProxy() {
    // not supported til https://ratpack.io/versions/1.8.0
    // TODO: add a latestDepTest
    return false
  }
}
