package com.meapet.mobile.telegram

import com.meapet.mobile.client.HttpMethod
import com.meapet.mobile.client.HttpResponse
import com.meapet.mobile.client.test.FakeHttpClientEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramBotClientTest {

    @Test
    fun `getMe parses valid bot info`() = runTest {
        val engine = FakeHttpClientEngine { req ->
            assertEquals(HttpMethod.GET, req.method)
            assertEquals("https://api.telegram.org/bot123:abc/getMe", req.url)
            HttpResponse(
                statusCode = 200,
                body = """{"ok":true,"result":{"id":987654,"is_bot":true,"first_name":"MeaBot","username":"mea_helper_bot"}}""".encodeToByteArray(),
                contentType = "application/json"
            )
        }
        val client = TelegramBotClient(engine = engine)

        val result = client.getMe(" 123:abc ")
        assertTrue(result.isSuccess)
        val info = result.getOrThrow()
        assertEquals(987654L, info.id)
        assertEquals("MeaBot", info.firstName)
        assertEquals("mea_helper_bot", info.username)
    }

    @Test
    fun `getMe returns failure on empty token or error response`() = runTest {
        val engine = FakeHttpClientEngine {
            HttpResponse(
                statusCode = 401,
                body = """{"ok":false,"error_code":401,"description":"Unauthorized"}""".encodeToByteArray(),
                contentType = "application/json"
            )
        }
        val client = TelegramBotClient(engine = engine)

        val emptyRes = client.getMe("   ")
        assertTrue(emptyRes.isFailure)

        val apiErrRes = client.getMe("bad-token")
        assertTrue(apiErrRes.isFailure)
        assertEquals("Unauthorized", apiErrRes.exceptionOrNull()?.message)
    }

    @Test
    fun `getUpdates parses message and channel_post updates with offset`() = runTest {
        val engine = FakeHttpClientEngine { req ->
            assertTrue(req.url.contains("/bot123:xyz/getUpdates?"))
            assertTrue(req.url.contains("offset=42"))
            HttpResponse(
                statusCode = 200,
                body = """
                    {
                      "ok": true,
                      "result": [
                        {
                          "update_id": 42,
                          "message": {
                            "date": 1700000000,
                            "chat": {"id": 111222},
                            "from": {"first_name": "Joel", "last_name": "Roux"},
                            "text": "  Coucou Mea !  "
                          }
                        },
                        {
                          "update_id": 43,
                          "channel_post": {
                            "date": 1700000010,
                            "chat": {"id": -100999, "title": "Mon Canal"},
                            "caption": "Notification depuis le canal"
                          }
                        },
                        {
                          "update_id": 44,
                          "message": {
                            "date": 1700000020,
                            "chat": {"id": 111222},
                            "text": "   "
                          }
                        }
                      ]
                    }
                """.trimIndent().encodeToByteArray(),
                contentType = "application/json"
            )
        }
        val client = TelegramBotClient(engine = engine)

        val res = client.getUpdates(botToken = "123:xyz", offset = 42L, timeoutSeconds = 10)
        assertTrue(res.isSuccess)
        val list = res.getOrThrow()
        assertEquals(2, list.size)

        assertEquals(42L, list[0].updateId)
        assertEquals(111222L, list[0].chatId)
        assertEquals("Joel Roux", list[0].senderName)
        assertEquals("Coucou Mea !", list[0].text)
        assertEquals(1700000000_000L, list[0].timestampMs)

        assertEquals(43L, list[1].updateId)
        assertEquals(-100999L, list[1].chatId)
        assertEquals("Mon Canal", list[1].senderName)
        assertEquals("Notification depuis le canal", list[1].text)
    }

    @Test
    fun `getUpdates propagates HTTP failure`() = runTest {
        val engine = FakeHttpClientEngine {
            HttpResponse(
                statusCode = 502,
                body = """{"ok":false,"description":"Bad Gateway"}""".encodeToByteArray(),
                contentType = "application/json"
            )
        }
        val client = TelegramBotClient(engine = engine)

        val res = client.getUpdates(botToken = "123:xyz")
        assertTrue(res.isFailure)
        assertEquals("Bad Gateway", res.exceptionOrNull()?.message)
    }
}
