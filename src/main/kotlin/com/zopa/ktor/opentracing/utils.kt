package com.zopa.ktor.opentracing

import io.opentracing.Span
import io.opentracing.Tracer
import io.opentracing.noop.NoopTracerFactory
import io.opentracing.tag.Tags
import io.opentracing.util.GlobalTracer
import kotlinx.coroutines.Job
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.asContextElement
import mu.KotlinLogging
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Stack
import kotlin.coroutines.coroutineContext


val log = KotlinLogging.logger { }

internal data class PathUuid(val path: String, val uuid: String?)
internal fun String.UuidFromPath(): PathUuid {
    val match = """\b[0-9a-fA-F]{8}\b-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-\b[0-9a-fA-F]{12}\b""".toRegex().find(this)

    if (match == null)
        return PathUuid(this, null)
    else {
        val uuid = match.value
        val pathWithReplacement = this.replace(uuid, "<UUID>")
        return PathUuid(pathWithReplacement, uuid)
    }
}

fun getGlobalTracer(): Tracer {
    return GlobalTracer.get()
        ?: NoopTracerFactory.create()
            .also { log.warn("Tracer not registered in GlobalTracer. Using Noop tracer instead.") }
}

suspend fun rootSpanContext(span: Span): ThreadContextElement<Stack<Span>> {
    span.addConfiguredLambdaTags()
    span.addCleanup()

    val spanStack = Stack<Span>()
    spanStack.push(span)

    return threadLocalSpanStack.asContextElement(spanStack)
}

fun closeSpan(statusCode: Int?) {
    val spanStack = threadLocalSpanStack.get()
    if (spanStack == null) {
        log.warn("spanStack is null")
        return
    }

    if (spanStack.isEmpty()) {
        log.error("Span could not be found in thread local trace context")
        return
    }
    val span = spanStack.pop()

    Tags.HTTP_STATUS.set(span, statusCode)
    if (statusCode == null || statusCode >= 400) {
        span.setTag("error", true)
    }

    span.finish()
}

internal suspend fun Span.addCleanup() {
    coroutineContext[Job]?.invokeOnCompletion {
        it?.also {
            val errors = StringWriter()
            it.printStackTrace(PrintWriter(errors))
            setTag("error", true)
            log(mapOf("stackTrace" to errors))
        }
        if (it != null) this.finish()
    }
}

fun Span.addConfiguredLambdaTags() {
    OpenTracingServer.config.lambdaTags.forEach {
        try {
            this.setTag(it.first, it.second.invoke())
        } catch (e: Exception) {
            log.warn(e) { "Could not add tag: ${it.first}" }
        }
    }
}

/*
    Helper function to name spans. Should only be used in method of a class as such:
    classAndMethodName(this, object {})
    Note that this function will give unexpected results if used in regular functions, extension functions and init functions. For these spans, it is preferable to define span names explicitly.
*/
fun classAndMethodName(
        currentInstance: Any,
        anonymousObjectCreatedInMethod: Any
): String {
    val className = currentInstance::class.simpleName

    val methodName: String = try {
        anonymousObjectCreatedInMethod.javaClass.enclosingMethod.name
    } catch (e: Exception) {
        ""
    }

    return "$className.$methodName()"
}

