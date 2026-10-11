package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.proxy.CodexFixedAccount
import com.example.proxy.ProxyAccount
import com.example.proxy.ProxyDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Codex Proxy", appName)
  }

  @Test
  fun `test database account insertion and retrieval`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = ProxyDatabase.getInstance(context)

    val initialAccounts = db.getAllAccounts()
    assertTrue(initialAccounts.isNotEmpty())
    assertEquals(CodexFixedAccount.ACCOUNT_ID, initialAccounts.first().accountId)

    val newId = db.insertAccount(
      ProxyAccount(
        name = "Test Unit Account",
        provider = "OpenAI",
        apiKey = "sk-test-12345",
        baseUrl = "https://api.openai.com/v1"
      )
    )
    assertTrue(newId > 0)

    val updatedAccounts = db.getAllAccounts()
    val found = updatedAccounts.find { it.id == newId }
    assertNotNull(found)
    assertEquals("Test Unit Account", found?.name)
  }

  @Test
  fun `test CCXProtocolConverter cross-protocol conversions`() {
    val codexPayload = """{"model":"gpt-4o","input":[{"type":"message","role":"user","content":[{"type":"text","text":"Hello CCX"}]}]}"""
    val convertedChat = com.example.proxy.CCXProtocolConverter.responsesToChat(codexPayload)
    assertTrue(convertedChat.contains("Hello CCX"))
    assertTrue(convertedChat.contains("messages"))

    val chatPayload = """{"model":"gpt-4o","messages":[{"role":"user","content":"Ping CCX"}]}"""
    val convertedResponses = com.example.proxy.CCXProtocolConverter.chatToResponses(chatPayload)
    assertTrue(convertedResponses.contains("Ping CCX"))
    assertTrue(convertedResponses.contains("input"))
  }
}
