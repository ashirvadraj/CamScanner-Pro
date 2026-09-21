package com.camscanner.pro.core.auth

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions

data class UserProfile(
    val id: String,
    val displayName: String?,
    val email: String?,
    val photoUrl: String?
)

object GoogleAuthManager {

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
        return GoogleSignIn.getLastSignedInAccount(context)
    }

    fun getUserProfile(context: Context): UserProfile? {
        val account = getSignedInAccount(context) ?: return null
        return UserProfile(
            id = account.id ?: "",
            displayName = account.displayName ?: "Google User",
            email = account.email,
            photoUrl = account.photoUrl?.toString()
        )
    }

    fun isSignedIn(context: Context): Boolean = getSignedInAccount(context) != null
}
