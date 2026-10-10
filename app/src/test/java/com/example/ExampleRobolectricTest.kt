package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
}
