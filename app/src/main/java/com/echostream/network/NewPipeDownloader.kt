package com.echostream.network

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response

/**
 * NewPipe [Downloader] implementation backed by [OkHttpClient].
 *
 * NewPipe extractor requires a [Downloader] to make HTTP requests on its behalf —
 * this bridges that interface to the app's existing OkHttp client so all requests
 * share the same connection pool, timeouts, and configuration.
 */
class NewPipeDownloader(private val client: OkHttpClient) : Downloader() {

    override fun execute(request: Request): Response {
        val builder = okhttp3.Request.Builder().url(request.url())

        request.headers().forEach { (name, values) ->
            values.forEach { value -> builder.addHeader(name, value) }
        }

        when (request.httpMethod()) {
            "POST" -> {
                val body = (request.dataToSend() ?: ByteArray(0)).toRequestBody()
                builder.post(body)
            }
            "HEAD" -> builder.head()
            else -> builder.get()
        }

        client.newCall(builder.build()).execute().use { response ->
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body?.string(),
                response.request.url.toString()
            )
        }
    }
}
