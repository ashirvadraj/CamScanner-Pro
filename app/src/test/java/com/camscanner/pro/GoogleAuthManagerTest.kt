package com.camscanner.pro

import com.camscanner.pro.core.auth.GoogleAuthManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class GoogleAuthManagerTest {

    @Test
    fun testDeriveNameFromEmailStandard() {
        val email = "ashirvadraj@gmail.com"
        val derived = GoogleAuthManager.deriveNameFromEmail(email)
        assertEquals("Ashirvadraj", derived)
    }

    @Test
    fun testDeriveNameFromEmailWithDotsAndNumbers() {
        val email = "ashirvad.raj.415@gmail.com"
        val derived = GoogleAuthManager.deriveNameFromEmail(email)
        assertEquals("Ashirvad Raj", derived)
    }

    @Test
    fun testDeriveNameFromEmailWithUnderscores() {
        val email = "john_doe_99@gmail.com"
        val derived = GoogleAuthManager.deriveNameFromEmail(email)
        assertEquals("John Doe", derived)
    }

    @Test
    fun testCreateProfileFromEmailCustomName() {
        val email = "test.user@gmail.com"
        val profile = GoogleAuthManager.createProfileFromEmail(email, "Ashirvad Raj")
        assertEquals("Ashirvad Raj", profile.displayName)
        assertEquals("test.user@gmail.com", profile.email)
        assertEquals("test.user@gmail.com", profile.id)
    }

    @Test
    fun testCreateProfileFromEmailAutomaticName() {
        val email = "alex.smith.101@gmail.com"
        val profile = GoogleAuthManager.createProfileFromEmail(email, null)
        assertEquals("Alex Smith", profile.displayName)
        assertEquals("alex.smith.101@gmail.com", profile.email)
    }
}
