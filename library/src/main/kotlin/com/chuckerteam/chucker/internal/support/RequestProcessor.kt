package com.chuckerteam.chucker.internal.support

import android.content.Context
import com.chuckerteam.chucker.R
import com.chuckerteam.chucker.api.BodyDecoder
import com.chuckerteam.chucker.api.ChuckerCollector
import com.chuckerteam.chucker.internal.data.entity.HttpTransaction
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.Request
import okio.Buffer
import okio.ByteString
import okio.IOException

internal class RequestProcessor(
    private val context: Context,
    private val collector: ChuckerCollector,
    private val maxContentLength: Long,
    private val headersToRedact: Set<String>,
    private val bodyDecoders: List<BodyDecoder>
) {
    fun process(request: Request, transaction: HttpTransaction) {
        processMetadata(request, transaction)
        processPayload(request, transaction)
        collector.onRequestSent(transaction)
    }

    private fun processMetadata(request: Request, transaction: HttpTransaction) {
        transaction.apply {
            requestHeadersSize = request.headers.byteCount()
            request.headers.redact(headersToRedact).let {
                setRequestHeaders(it)
                setGraphQlOperationName(it)
                if (request.method == "GET") {
                    request.url.queryParameter("operationName")?.let { name ->
                        setGraphQlOperationNameString(name)
                    }
                } else {
                    request.body?.let {body ->
                        val gson = Gson()
                        val buffer = Buffer()
                        body.writeTo(buffer)
                        val bodyString = buffer.readUtf8()
                        if (bodyString.startsWith("[")) {
                            gson.fromJson<List<Map<String, Any>>>(
                                bodyString,
                                List::class.java
                            ).let {list ->
                                val name = list.map { v -> v["operationName"] }.joinToString() //(separator = "\n") { "- $it" }
                                setGraphQlOperationNameString(name)
                            }
                        }
                        if (bodyString.startsWith("{")){
                            setGraphQlOperationNameString(
                                gson.fromJson<Map<String, Any>>(
                                    bodyString,
                                    object : TypeToken<Map<String, Any>>() {}.type
                                )["operationName"].toString()
                            )
                        }
                    }
                }
            }
            populateUrl(request.url)
            graphQlDetected = isGraphQLRequest(this.graphQlOperationName, request)

            requestDate = System.currentTimeMillis()
            method = request.method
            requestContentType = request.body?.contentType()?.toString()
            requestPayloadSize = request.body?.contentLength()
        }
    }

    private fun processPayload(request: Request, transaction: HttpTransaction) {
        val body = request.body ?: return
        if (body.isOneShot()) {
            Logger.info("Skipping one shot request body")
            return
        }
        if (body.isDuplex()) {
            Logger.info("Skipping duplex request body")
            return
        }

        val requestSource = try {
            Buffer().apply { body.writeTo(this) }
        } catch (e: IOException) {
            Logger.error("Failed to read request payload", e)
            return
        }
        val limitingSource = LimitingSource(requestSource.uncompress(request.headers), maxContentLength)

        val contentBuffer = Buffer().apply { limitingSource.use { writeAll(it) } }

        val decodedContent = decodePayload(request, contentBuffer.readByteString())
        transaction.requestBody = decodedContent
        transaction.isRequestBodyEncoded = decodedContent == null
        if (decodedContent != null && limitingSource.isThresholdReached) {
            transaction.requestBody += context.getString(R.string.chucker_body_content_truncated)
        }
    }

    private fun decodePayload(request: Request, body: ByteString) = bodyDecoders.asSequence()
        .mapNotNull { decoder ->
            try {
                Logger.info("Decoding with: $decoder")
                decoder.decodeRequest(request, body)
            } catch (e: IOException) {
                Logger.warn("Decoder $decoder failed to process request payload", e)
                null
            }
        }.firstOrNull()

    private fun isGraphQLRequest(graphQLOperationName: String?, request: Request) =
        graphQLOperationName != null ||
            request.url.pathSegments.contains("graphql") ||
            request.url.host.contains("graphql")
}
