package com.camscanner.pro.core.auth

import android.content.Context
import android.content.SharedPreferences
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import java.util.Locale

data class UserProfile(
    val id: String,
    val displayName: String?,
    val email: String?,
    val photoUrl: String?
)

object GoogleAuthManager {

    private const val PREFS_NAME = "camscanner_auth_prefs"
    private const val KEY_IS_SIGNED_IN = "key_is_signed_in"
    private const val KEY_USER_ID = "key_user_id"
    private const val KEY_DISPLAY_NAME = "key_display_name"
    private const val KEY_EMAIL = "key_email"
    private const val KEY_PHOTO_URL = "key_photo_url"

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun getGso(): GoogleSignInOptions {
        return GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
            .build()
    }

    fun getClient(context: Context): GoogleSignInClient {
        return GoogleSignIn.getClient(context, getGso())
    }

    fun getSignedInAccount(context: Context): GoogleSignInAccount? {
        return try {
            GoogleSignIn.getLastSignedInAccount(context)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Saves user profile into persistent preferences so the user's name
     * and backup connection remain active across app sessions and restarts.
     */
    fun saveUserProfile(context: Context, profile: UserProfile) {
        getPrefs(context).edit()
            .putBoolean(KEY_IS_SIGNED_IN, true)
            .putString(KEY_USER_ID, profile.id)
            .putString(KEY_DISPLAY_NAME, profile.displayName)
            .putString(KEY_EMAIL, profile.email)
            .putString(KEY_PHOTO_URL, profile.photoUrl)
            .apply()
    }

    /**
     * Retrieves the current user profile. Checks Google Play Services account first,
     * then falls back to persistent local storage so login isn't lost if offline or
     * if Google Play Services OAuth reports code 10.
     */
    fun getUserProfile(context: Context): UserProfile? {
        // 1. Check Google Play Services
        val account = getSignedInAccount(context)
        if (account != null) {
            val profile = UserProfile(
                id = account.id ?: account.email ?: "google_user",
                displayName = account.displayName ?: deriveNameFromEmail(account.email),
                email = account.email,
                photoUrl = account.photoUrl?.toString()
            )
            // Cache locally
            saveUserProfile(context, profile)
            return profile
        }

        // 2. Check persistent preferences
        val prefs = getPrefs(context)
        if (prefs.getBoolean(KEY_IS_SIGNED_IN, false)) {
            val email = prefs.getString(KEY_EMAIL, null)
            val name = prefs.getString(KEY_DISPLAY_NAME, null) ?: deriveNameFromEmail(email)
            val id = prefs.getString(KEY_USER_ID, email ?: "google_user") ?: "google_user"
            val photoUrl = prefs.getString(KEY_PHOTO_URL, null)

            if (!email.isNullOrBlank() || !name.isNullOrBlank()) {
                return UserProfile(
                    id = id,
                    displayName = name,
                    email = email,
                    photoUrl = photoUrl
                )
            }
        }

        return null
    }

    fun isSignedIn(context: Context): Boolean = getUserProfile(context) != null

    fun signOut(context: Context, onComplete: () -> Unit = {}) {
        // Clear local credentials
        getPrefs(context).edit().clear().apply()

        // Also sign out from Google Play Services client if available
        try {
            getClient(context).signOut().addOnCompleteListener {
                onComplete()
            }
        } catch (e: Exception) {
            onComplete()
        }
    }

    /**
     * Creates a well-formatted UserProfile from an email address and optional name.
     */
    fun createProfileFromEmail(email: String, name: String? = null): UserProfile {
        val cleanName = if (!name.isNullOrBlank()) {
            name.trim()
        } else {
            deriveNameFromEmail(email)
        }

        return UserProfile(
            id = email.lowercase(Locale.ROOT),
            displayName = cleanName,
            email = email.trim(),
            photoUrl = null
        )
    }

    /**
     * Converts an email like "ashirvadraj415@gmail.com" into "Ashirvad Raj"
     */
    fun deriveNameFromEmail(email: String?): String {
        if (email.isNullOrBlank()) return "Google User"
        val prefix = email.substringBefore("@")
        // Remove trailing digits and punctuation
        val lettersOnly = prefix.replace("[0-9._-]+".toRegex(), " ").trim()
        return if (lettersOnly.isNotBlank()) {
            lettersOnly.split(" ")
                .filter { it.isNotBlank() }
                .joinToString(" ") { it.replaceFirstChar { c -> c.titlecase(Locale.getDefault()) } }
        } else {
            prefix.replaceFirstChar { c -> c.titlecase(Locale.getDefault()) }
        }
    }
}
