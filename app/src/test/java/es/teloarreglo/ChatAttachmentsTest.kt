package es.teloarreglo

import es.teloarreglo.chat.*
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.minimalSettings
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.resumable.MemoryResumableCache
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ChatAttachmentsTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val message = """{"id":"message","conversation_id":"conversation","author_id":"client","request_id":"key",
        "body":"","created_at":"2026-11-22T10:00:00Z","attachment_kind":"photo",
        "attachment_path":"conversation/key.jpg","attachment_type":"image/jpeg","attachment_size":3}"""
    private val stored = """{"Id":"object","Key":"chat-attachments/conversation/key.jpg"}"""
    private val reserved = """{"object_path":"conversation/key.jpg","status":"uploading","message":null}"""

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): SupabaseClient =
        createSupabaseClient("https://photos.example.org", "public-key") {
            defaultLogLevel = LogLevel.NONE
            install(Auth) { minimalSettings(sessionManager = MemorySessionManager()) }
            install(Postgrest)
            install(Storage) { resumable { cache = MemoryResumableCache() } }
            httpEngine = MockEngine(handler)
        }

    private suspend fun failureOf(block: suspend () -> Unit): AttachmentFailure? = try {
        block(); null
    } catch (error: ChatAttachmentException) { error.failure }

    @Test fun photoBecomesAMessageOnlyAfterStorageHoldsTheCompressedFile() = runTest {
        val calls = mutableListOf<String>()
        val client = client { request ->
            val path = request.url.encodedPath
            calls.add(path)
            when {
                path.endsWith("/request_chat_attachment") -> {
                    val body = (request.body as TextContent).text
                    assertTrue(body.contains("\"p_kind\":\"photo\""))
                    assertTrue(body.contains("\"p_mime\":\"image/jpeg\""))
                    // SHA-256 of bytes 1,2,3, computed independently.
                    assertTrue(body.contains("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81"))
                    respond(reserved, HttpStatusCode.OK, json)
                }
                path.contains("/storage/v1/object/") -> respond(stored, HttpStatusCode.OK, json)
                else -> respond(message, HttpStatusCode.OK, json)
            }
        }
        try {
            val events = SupabaseChatAttachments(client)
                .send("conversation", "key", AttachmentKind.PHOTO, "image/jpeg", byteArrayOf(1, 2, 3)).toList()
            assertEquals(listOf("/rest/v1/rpc/request_chat_attachment",
                "/storage/v1/object/chat-attachments/conversation/key.jpg",
                "/rest/v1/rpc/submit_chat_attachment"), calls)
            assertTrue(events.first() is AttachmentProgress.Uploading)
            val sent = events.last() as AttachmentProgress.Sent
            assertEquals("conversation/key.jpg", sent.message.attachmentPath)
            assertEquals("photo", sent.message.attachmentKind)
        } finally { client.close() }
    }

    @Test fun aVideoTravelsTheSamePrivatePathAsAnMp4WithItsContentType() = runTest {
        val uploads = mutableListOf<Pair<String, String?>>()
        val client = client { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/request_chat_attachment") -> {
                    val body = (request.body as TextContent).text
                    assertTrue(body.contains("\"p_kind\":\"video\""))
                    assertTrue(body.contains("\"p_mime\":\"video/mp4\""))
                    respond("""{"object_path":"conversation/key.mp4","status":"uploading","message":null}""",
                        HttpStatusCode.OK, json)
                }
                path.contains("/storage/v1/object/") -> {
                    uploads.add(path to request.body.contentType?.toString())
                    respond(stored, HttpStatusCode.OK, json)
                }
                else -> respond(message.replace("\"photo\"", "\"video\"").replace("key.jpg", "key.mp4")
                    .replace("image/jpeg", "video/mp4"), HttpStatusCode.OK, json)
            }
        }
        try {
            val events = SupabaseChatAttachments(client)
                .send("conversation", "key", AttachmentKind.VIDEO, "video/mp4", ByteArray(4096) { 1 }).toList()
            assertEquals("/storage/v1/object/chat-attachments/conversation/key.mp4", uploads.single().first)
            assertEquals("video/mp4", uploads.single().second)
            assertEquals("video", (events.last() as AttachmentProgress.Sent).message.attachmentKind)
        } finally { client.close() }
    }

    @Test fun aLostConfirmationIsRecoveredWithoutUploadingTheFileAgain() = runTest {
        val calls = mutableListOf<String>()
        val client = client { request ->
            calls.add(request.url.encodedPath)
            respond("""{"object_path":"conversation/key.jpg","status":"sent","message":$message}""", HttpStatusCode.OK, json)
        }
        try {
            val events = SupabaseChatAttachments(client)
                .send("conversation", "key", AttachmentKind.PHOTO, "image/jpeg", byteArrayOf(1, 2, 3)).toList()
            assertEquals(listOf("/rest/v1/rpc/request_chat_attachment"), calls)
            assertEquals("message", (events.single() as AttachmentProgress.Sent).message.id)
        } finally { client.close() }
    }

    @Test fun aDuplicateUploadAfterALostResponseStillConfirmsTheSameMessage() = runTest {
        val client = client { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/request_chat_attachment") -> respond(reserved, HttpStatusCode.OK, json)
                path.contains("/storage/v1/object/") -> respond(
                    """{"statusCode":"409","error":"Duplicate","message":"The resource already exists","code":"KeyAlreadyExists"}""",
                    HttpStatusCode.BadRequest, json)
                else -> respond(message, HttpStatusCode.OK, json)
            }
        }
        try {
            val events = SupabaseChatAttachments(client)
                .send("conversation", "key", AttachmentKind.PHOTO, "image/jpeg", byteArrayOf(1, 2, 3)).toList()
            assertTrue(events.last() is AttachmentProgress.Sent)
        } finally { client.close() }
    }

    private suspend fun storageRejection(body: String): Pair<AttachmentFailure?, Boolean> {
        var submitted = false
        val client = client { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/request_chat_attachment") -> respond(reserved, HttpStatusCode.OK, json)
                path.endsWith("/submit_chat_attachment") -> { submitted = true; respond(message, HttpStatusCode.OK, json) }
                else -> respond(body, HttpStatusCode.BadRequest, json)
            }
        }
        try {
            return failureOf {
                SupabaseChatAttachments(client)
                    .send("conversation", "key", AttachmentKind.PHOTO, "image/jpeg", byteArrayOf(1)).toList()
            } to submitted
        } finally { client.close() }
    }

    @Test fun anOversizedFileIsExplainedAndNeverSent() = runTest {
        assertEquals(AttachmentFailure.TOO_LARGE to false, storageRejection(
            """{"statusCode":"413","error":"Payload too large","message":"The object exceeded the maximum allowed size","code":"EntityTooLarge"}"""))
    }

    @Test fun anIncompatibleFormatIsExplainedAndNeverSent() = runTest {
        assertEquals(AttachmentFailure.UNSUPPORTED to false, storageRejection(
            """{"statusCode":"415","error":"invalid_mime_type","message":"mime type image/gif is not supported","code":"InvalidMimeType"}"""))
    }

    @Test fun anExhaustedStorageQuotaIsExplainedAndNeverSent() = runTest {
        assertEquals(AttachmentFailure.QUOTA to false, storageRejection(
            """{"statusCode":"402","error":"Quota exceeded","message":"Storage quota exceeded for this project"}"""))
    }

    @Test fun aStorageRlsDenialIsAPermissionProblemNotUncertainDelivery() = runTest {
        assertEquals(AttachmentFailure.ACCESS to false, storageRejection(
            """{"statusCode":"403","error":"Unauthorized","message":"new row violates row-level security policy"}"""))
    }

    @Test fun aChatPermissionRejectionIsReportedAsAccess() = runTest {
        val client = client {
            respond("""{"code":"42501","message":"permission_denied","details":null,"hint":null}""",
                HttpStatusCode.Forbidden, json)
        }
        try {
            assertEquals(AttachmentFailure.ACCESS, failureOf {
                SupabaseChatAttachments(client)
                    .send("conversation", "key", AttachmentKind.PHOTO, "image/jpeg", byteArrayOf(1)).toList()
            })
        } finally { client.close() }
    }

    @Test fun aServerSizeRejectionAtConfirmationIsReportedAsTooLarge() = runTest {
        val client = client { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/request_chat_attachment") -> respond(reserved, HttpStatusCode.OK, json)
                path.contains("/storage/v1/object/") -> respond(stored, HttpStatusCode.OK, json)
                else -> respond("""{"code":"22023","message":"attachment_too_large","details":null,"hint":null}""",
                    HttpStatusCode.BadRequest, json)
            }
        }
        try {
            assertEquals(AttachmentFailure.TOO_LARGE, failureOf {
                SupabaseChatAttachments(client)
                    .send("conversation", "key", AttachmentKind.PHOTO, "image/jpeg", byteArrayOf(1)).toList()
            })
        } finally { client.close() }
    }

    @Test fun aNetworkFailureDuringUploadIsUncertainAndDoesNotConfirm() = runTest {
        var submitted = false
        val client = client { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/request_chat_attachment") -> respond(reserved, HttpStatusCode.OK, json)
                path.endsWith("/submit_chat_attachment") -> { submitted = true; respond(message, HttpStatusCode.OK, json) }
                else -> throw java.io.IOException("connection lost")
            }
        }
        try {
            var uncertain = false
            try {
                SupabaseChatAttachments(client)
                    .send("conversation", "key", AttachmentKind.PHOTO, "image/jpeg", byteArrayOf(1)).toList()
            } catch (_: java.io.IOException) { uncertain = true }
            assertTrue(uncertain)
            assertFalse(submitted)
        } finally { client.close() }
    }

    @Test fun cancellingDuringUploadNeverConfirmsTheMessage() = runTest {
        var submitted = false
        val uploading = CompletableDeferred<Unit>()
        val client = client { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/request_chat_attachment") -> respond(reserved, HttpStatusCode.OK, json)
                path.endsWith("/submit_chat_attachment") -> { submitted = true; respond(message, HttpStatusCode.OK, json) }
                else -> { uploading.complete(Unit); awaitCancellation() }
            }
        }
        try {
            val job = launch {
                SupabaseChatAttachments(client)
                    .send("conversation", "key", AttachmentKind.PHOTO, "image/jpeg", byteArrayOf(1)).toList()
            }
            uploading.await()
            job.cancel()
            job.join()
            assertTrue(job.isCancelled)
            assertFalse(submitted)
        } finally { client.close() }
    }

    @Test fun openingAPhotoUsesAShortLivedSignedUrlOfThePrivateBucket() = runTest {
        val client = client { request ->
            assertEquals("/storage/v1/object/sign/chat-attachments/conversation/key.jpg", request.url.encodedPath)
            val body = (request.body as TextContent).text
            assertTrue(body.contains("\"expiresIn\":600"))
            respond("""{"signedURL":"/object/sign/chat-attachments/conversation/key.jpg?token=t"}""", HttpStatusCode.OK, json)
        }
        try {
            val url = SupabaseChatAttachments(client).url("conversation/key.jpg")
            assertTrue(url.startsWith("https://photos.example.org/storage/v1/object/sign/chat-attachments/"))
        } finally { client.close() }
    }
}
