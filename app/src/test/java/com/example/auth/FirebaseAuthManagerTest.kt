package com.example.auth

import com.example.base.FirestoreEmulatorTestBase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FirebaseAuthManagerTest : FirestoreEmulatorTestBase() {

    @Test
    fun testInitialStateWhenUnauthenticated() = runBlocking {
        auth.signOut()
        val manager = FirebaseAuthManager(auth)

        assertNull(manager.currentUser.first())
        assertFalse(manager.isAuthenticated)
        assertNull(manager.currentUserId)
        assertEquals(AuthState.Unauthenticated, manager.authState.first())
    }

    @Test
    fun testSignOutResetsState() = runBlocking {
        val manager = FirebaseAuthManager(auth)
        manager.signOut()

        assertNull(manager.currentUser.first())
        assertFalse(manager.isAuthenticated)
        assertEquals(AuthState.Unauthenticated, manager.authState.first())
    }

    @Test
    fun testAuthStateHierarchy() {
        val errorState = AuthState.Error("Invalid token", IllegalArgumentException("Bad token"))
        assertEquals("Invalid token", errorState.message)
        assertTrue(errorState.cause is IllegalArgumentException)
        assertEquals(AuthState.Loading, AuthState.Loading)
        assertEquals(AuthState.Unauthenticated, AuthState.Unauthenticated)
    }
}
