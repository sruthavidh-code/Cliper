package com.example.ai

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory

private const val TAG = "AppCheckInit"

private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"

/**
 * Ensures the default [FirebaseApp] is initialized for this process.
 *
 * 1. Returns the existing default instance if [com.google.firebase.provider.FirebaseInitProvider]
 *    or an earlier call already initialized it.
 * 2. Tries standard [FirebaseApp.initializeApp] (which reads `google_app_id` via Play Services'
 *    `StringResourceValueReader`).
 * 3. Falls back to resolving the Google Services string resources directly against both
 *    `context.packageName` (`applicationId`) and the app module namespace (`com.example`) in case
 *    `StringResourceValueReader` resolved a different resource package on a device or split APK.
 */
fun ensureFirebaseAppInitialized(context: Context): FirebaseApp? {
    val appContext = context.applicationContext ?: context
    FirebaseApp.getApps(appContext).firstOrNull()?.let { return it }

    val defaultApp = try {
        FirebaseApp.initializeApp(appContext)
    } catch (e: Exception) {
        Log.e(TAG, "Standard FirebaseApp.initializeApp(context) failed", e)
        null
    }
    if (defaultApp != null) {
        return defaultApp
    }

    val fallbackOptions = resolveFirebaseOptionsFromResources(appContext)
    if (fallbackOptions == null) {
        Log.e(
            TAG,
            "Firebase string resources (google_app_id / google_api_key) were not found in package " +
                "${appContext.packageName}. Ensure app/google-services.json is present during Gradle build."
        )
        return null
    }

    return try {
        FirebaseApp.initializeApp(appContext, fallbackOptions)
    } catch (e: IllegalStateException) {
        // Another thread initialized [DEFAULT] concurrently
        FirebaseApp.getApps(appContext).firstOrNull()
    } catch (e: Exception) {
        Log.e(TAG, "Fallback FirebaseApp.initializeApp(context, options) failed", e)
        null
    }
}

private fun resolveFirebaseOptionsFromResources(context: Context): FirebaseOptions? {
    val candidatePackages = linkedSetOf(
        context.packageName,
        "com.example"
    )

    fun readString(resName: String): String? {
        for (pkg in candidatePackages) {
            val id = context.resources.getIdentifier(resName, "string", pkg)
            if (id != 0) {
                val value = runCatching { context.getString(id) }.getOrNull()?.trim()
                if (!value.isNullOrEmpty()) return value
            }
        }
        return null
    }

    val appId = readString("google_app_id") ?: return null
    val apiKey = readString("google_api_key") ?: return null

    val builder = FirebaseOptions.Builder()
        .setApplicationId(appId)
        .setApiKey(apiKey)

    readString("project_id")?.let { builder.setProjectId(it) }
    readString("gcm_defaultSenderId")?.let { builder.setGcmSenderId(it) }
    readString("google_storage_bucket")?.let { builder.setStorageBucket(it) }
    readString("firebase_database_url")?.let { builder.setDatabaseUrl(it) }

    return builder.build()
}

@SuppressLint("ApplySharedPref")
fun configureAppCheck(context: Context, intent: Intent?) {
    try {
        val firebaseApp = ensureFirebaseAppInitialized(context)
        if (firebaseApp == null) {
            Log.e(
                TAG,
                "Cannot configure Firebase App Check because default FirebaseApp failed to initialize."
            )
            return
        }

        if (BuildConfig.DEBUG) {
            val debugToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)

            if (!debugToken.isNullOrEmpty()) {
                val persistenceKey = firebaseApp.persistenceKey
                val prefs = context.getSharedPreferences(
                    "$DEBUG_STORE_PREFIX$persistenceKey",
                    Context.MODE_PRIVATE
                )
                // Synchronous commit() is mandatory so DebugAppCheckProviderFactory reads the secret immediately.
                prefs.edit()
                    .putString(DEBUG_SECRET_KEY, debugToken)
                    .commit()
                Log.d(TAG, "App Check debug secret configured successfully.")
            } else {
                Log.w(TAG, "No debug token found in Intent extra.")
            }

            FirebaseAppCheck.getInstance(firebaseApp).installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance()
            )
            Log.d(TAG, "DebugAppCheckProviderFactory installed.")
        } else {
            val resId = context.resources.getIdentifier(
                RECAPTCHA_SITE_KEY_RES,
                "string",
                context.packageName
            )
            if (resId != 0) {
                val siteKey = context.getString(resId)
                if (siteKey.isNotBlank()) {
                    FirebaseAppCheck.getInstance(firebaseApp).installAppCheckProviderFactory(
                        RecaptchaAppCheckProviderFactory.getInstance(siteKey)
                    )
                    Log.d(TAG, "RecaptchaAppCheckProviderFactory installed.")
                } else {
                    Log.e(TAG, "recaptcha_site_key resource string is blank.")
                }
            } else {
                Log.e(TAG, "recaptcha_site_key string resource not found in package.")
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize Firebase App Check", e)
    }
}
