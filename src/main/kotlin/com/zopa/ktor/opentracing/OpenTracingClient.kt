package com.zopa.ktor.opentracing

import io.ktor.client.HttpClient
import io.ktor.client.features.HttpClientFeature
import io.ktor.client.request.HttpSendPipeline
import io.ktor.client.statement.HttpReceivePipeline
import io.ktor.util.AttributeKey
import io.opentracing.Tracer
import io.opentracing.propagation.Format
import io.opentracing.tag.Tags


class OpenTracingClient {
    class Config

    companion object : HttpClientFeature<Config, OpenTracingClient> {
        override val key: AttributeKey<OpenTracingClient> = AttributeKey("OpenTracingClient")

        override fun prepare(block: Config.() -> Unit): OpenTracingClient {
            return OpenTracingClient()
        }

        override fun install(feature: OpenTracingClient, scope: HttpClient) {

            val tracer: Tracer = getGlobalTracer()

            scope.sendPipeline.intercept(HttpSendPipeline.State) {
                val spanStack = threadLocalSpanStack.get()
                if (spanStack == null) {
                    log.warn("spanStack is null")
                    return@intercept
                }

                val pathUuid: PathUuid = context.url.encodedPath.UuidFromPath()
                val name = "Call to ${context.method.value} ${context.url.host}${pathUuid.path}"

                val spanBuilder = tracer.buildSpan(name)
                if (pathUuid.uuid != null) spanBuilder.withTag("UUID", pathUuid.uuid)
                if (spanStack.isNotEmpty()) spanBuilder?.asChildOf(spanStack.peek())
                val span = spanBuilder?.start()
                span?.addCleanup()
                span?.addConfiguredLambdaTags()

                Tags.SPAN_KIND.set(span, Tags.SPAN_KIND_CLIENT)
                Tags.HTTP_METHOD.set(span, context.method.value)
                Tags.HTTP_URL.set(span, "${context.url.host}${context.url.encodedPath}")

                spanStack.push(span)
                tracer.inject(span?.context(), Format.Builtin.HTTP_HEADERS, RequestBuilderCarrier(context.headers))
            }

            scope.receivePipeline.intercept(HttpReceivePipeline.State) {
                val statusCode = context.response.status
                closeSpan(statusCode.value)
            }
        }
    }
}
