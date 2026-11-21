package com.example.ai

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.ai
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FirebaseInitTest {

    @Test
    fun `ensureFirebaseAppInitialized and configureAppCheck initialize default FirebaseApp and AI model`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val launchIntent = Intent().apply {
            putExtra("FIREBASE_APPCHECK_DEBUG_TOKEN", "test-debug-token-123")
        }

        configureAppCheck(context, launchIntent)

        val app = ensureFirebaseAppInitialized(context)
        assertNotNull("Expected default FirebaseApp to be initialized", app)
        assertFalse(app!!.options.applicationId.isBlank())
        assertFalse(app.options.apiKey.isBlank())
        assertTrue(FirebaseApp.getApps(context).isNotEmpty())

        val prefs = context.getSharedPreferences(
            "com.google.firebase.appcheck.debug.store.${app.persistenceKey}",
            Context.MODE_PRIVATE
        )
        assertEquals(
            "test-debug-token-123",
            prefs.getString("com.google.firebase.appcheck.debug.DEBUG_SECRET", null)
        )

        val model = Firebase.ai.generativeModel(GEMINI_FLASH_MODEL)
        assertNotNull("Expected Firebase.ai.generativeModel to resolve with default FirebaseApp", model)
    }
}
