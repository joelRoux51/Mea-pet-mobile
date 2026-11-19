package com.meapet.mobile.telegram

import com.meapet.mobile.chat.ChatRole
import com.meapet.mobile.chat.ConversationManager
import com.meapet.mobile.settings.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class LocalWebhookServerTest {

    private lateinit var settingsManager: SettingsManager
    private lateinit var conversationManager: ConversationManager
    private lateinit var notificationHelper: TelegramNotificationHelper
    private lateinit var scope: CoroutineScope
    private lateinit var server: LocalWebhookServer
    private val triggeredEmotions = mutableListOf<String>()
    private var port: Int = 0

    @Before
    fun setUp() {
        settingsManager = mock()
        conversationManager = ConversationManager()
        notificationHelper = mock()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        triggeredEmotions.clear()
        server = LocalWebhookServer(
            settingsManager = settingsManager,
            conversationManager = conversationManager,
            notificationHelper = notificationHelper,
            scope = scope,
            port = 0,
            host = "127.0.0.1",
            onEmotionReceived = { emotion -> synchronized(triggeredEmotions) { triggeredEmotions.add(emotion) } }
        )
        port = server.startListening()
    }

    @After
    fun tearDown() {
        server.stop()
        scope.cancel()
    }

    private fun postJson(path: String, jsonBody: String, method: String = "POST"): Pair<Int, String> {
        val conn = (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 3_000
            readTimeout = 3_000
            if (method == "POST") {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        if (method == "POST") {
            conn.outputStream.use { os ->
                os.write(jsonBody.toByteArray(StandardCharsets.UTF_8))
            }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val responseBody = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
        conn.disconnect()
        return code to responseBody
    }

    @Test
    fun `POST notify stores history emits bubble event and shows notification`() = runBlocking {
        val emitted = mutableListOf<WebhookMessageEvent>()
        val collector = launch {
            emitted.add(withTimeout(3_000) { server.events.first() })
        }
        // Let collector subscribe before firing HTTP request
        kotlinx.coroutines.delay(50)

        val text = "C'est l'heure de boire de l'eau !"
        val (status, body) = postJson("/notify", """{"text":"$text"}""")

        assertEquals(200, status)
        assertTrue(body.contains("\"ok\":true"))

        collector.join()
        assertEquals(1, emitted.size)
        assertEquals(LocalWebhookServer.DEFAULT_SENDER, emitted[0].sender)
        assertEquals(text, emitted[0].text)
        assertTrue(emitted[0].message.isFromTelegram)

        val history = conversationManager.getMessages()
        assertEquals(1, history.size)
        assertEquals(ChatRole.assistant, history[0].role)
        assertEquals(text, history[0].content)
        assertTrue(history[0].isFromTelegram)

        verify(notificationHelper).showNotification(LocalWebhookServer.DEFAULT_SENDER, text)
    }

    @Test
    fun `POST notify respects custom sender`() = runBlocking {
        val emitted = mutableListOf<WebhookMessageEvent>()
        val collector = launch {
            emitted.add(withTimeout(3_000) { server.events.first() })
        }
        kotlinx.coroutines.delay(50)

        val (status, _) = postJson("/notify", """{"text":"Bonjour !","sender":"Alita Python"}""")

        assertEquals(200, status)
        collector.join()
        assertEquals("Alita Python", emitted[0].sender)
        verify(notificationHelper).showNotification("Alita Python", "Bonjour !")
    }

    @Test
    fun `POST notify rejects blank text and invalid json with 400`() {
        val (statusBlank, _) = postJson("/notify", """{"text":"   "}""")
        assertEquals(400, statusBlank)

        val (statusInvalid, _) = postJson("/notify", """not-json""")
        assertEquals(400, statusInvalid)

        assertEquals(0, conversationManager.getMessages().size)
        verify(notificationHelper, never()).showNotification(org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    @Test
    fun `unsupported method and unknown path return 405 and 404`() {
        val (getStatus, _) = postJson("/notify", "", method = "GET")
        assertEquals(405, getStatus)

        val (notFoundStatus, _) = postJson("/other", """{"text":"hi"}""")
        assertEquals(404, notFoundStatus)
    }

    @Test
    fun `POST notify extracts EMOTION tag triggers expression and strips tag from text`() = runBlocking {
        val emitted = mutableListOf<WebhookMessageEvent>()
        val collector = launch {
            emitted.add(withTimeout(3_000) { server.events.first() })
        }
        kotlinx.coroutines.delay(50)

        val (status, body) = postJson(
            "/notify",
            """{"text":"[EMOTION: joie] C'est l'heure de boire de l'eau !"}"""
        )

        assertEquals(200, status)
        assertTrue(body.contains("\"ok\":true"))
        collector.join()

        val expectedClean = "C'est l'heure de boire de l'eau !"
        assertEquals(expectedClean, emitted[0].text)
        assertEquals("joie", emitted[0].emotion)
        assertEquals(listOf("joie"), triggeredEmotions)

        val history = conversationManager.getMessages()
        assertEquals(1, history.size)
        assertEquals(expectedClean, history[0].content)
        verify(notificationHelper).showNotification(LocalWebhookServer.DEFAULT_SENDER, expectedClean)
    }

    @Test
    fun `POST notify with only EMOTION tag triggers expression without adding empty bubble`() = runBlocking {
        val (status, body) = postJson("/notify", """{"text":"[emotion : colère]"}""")

        assertEquals(200, status)
        assertTrue(body.contains("\"ok\":true"))
        assertEquals(listOf("colere"), triggeredEmotions)
        assertEquals(0, conversationManager.getMessages().size)
        verify(notificationHelper, never()).showNotification(org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    @Test
    fun `resolveLocalServerUrls includes loopback default`() {
        val urls = LocalWebhookServer.resolveLocalServerUrls(8081)
        assertTrue(urls.isNotEmpty())
        assertEquals("http://127.0.0.1:8081", urls.first())
    }

    @Test
    fun `POST notify infers calm praying expression on Bible verse and surprise on alarm when tag omitted`() {
        val (s1, _) = postJson("/notify", """{"text":"Verset du matin : Psaume 23:1"}""")
        val (s2, _) = postJson("/notify", """{"text":"⏰ DEBOUT ! C'est l'heure du réveil !"}""")
        assertEquals(200, s1)
        assertEquals(200, s2)
        assertEquals(listOf("calme", "surprise"), triggeredEmotions)
    }
}
