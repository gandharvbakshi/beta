package com.example.beta

import org.junit.Assert.*
import org.junit.Test

class SwiggyConnectionSessionTest {
    @Test fun temporaryFailurePreservesKnownConnectionButBlocksUseUntilRechecked() {
        val session = SwiggyConnectionSession()
        session.confirm(SwiggyMcpClient.ConnectionState.READY)
        val request = session.beginStatus()!!
        assertFalse(session.canUseConnection)
        assertTrue(session.settleStatus(request))
        session.fail(reconnectRequired = false)
        assertEquals(SwiggyMcpClient.ConnectionState.READY, session.state)
        assertEquals(SwiggyConnectionSession.Presentation.RETRY, session.presentation)
        assertFalse(session.canUseConnection)
        val retry = session.beginStatus()!!
        assertTrue(session.settleStatus(retry))
        session.confirm(SwiggyMcpClient.ConnectionState.READY)
        assertTrue(session.canUseConnection)
    }

    @Test fun oauthReturnSupersedesPreLoginStatusWithoutTrustingDeepLink() {
        val session = SwiggyConnectionSession()
        val old = session.beginStatus()!!
        session.invalidate()
        val fresh = session.beginStatus()!!
        assertFalse(session.canUseConnection)
        assertFalse(session.settleStatus(old))
        assertNull(session.beginStatus())
        assertTrue(session.settleStatus(fresh))
        session.confirm(SwiggyMcpClient.ConnectionState.READY)
        assertTrue(session.canUseConnection)
        assertFalse(session.settleStatus(old))
        assertFalse(session.settleStatus(fresh))
    }

    @Test fun onlyExplicitReauthFailureAsksForLogin() {
        val session = SwiggyConnectionSession()
        session.fail(false)
        assertEquals(SwiggyConnectionSession.Presentation.RETRY, session.presentation)
        session.fail(true)
        assertEquals(SwiggyMcpClient.ConnectionState.RECONNECT_REQUIRED, session.state)
        assertEquals(SwiggyConnectionSession.Presentation.CONFIRMED, session.presentation)
    }

    @Test fun disconnectAndRecreationCannotReuseReadyState() {
        val session = SwiggyConnectionSession()
        session.confirm(SwiggyMcpClient.ConnectionState.READY)
        val old = session.beginStatus()!!
        session.invalidate()
        session.confirm(SwiggyMcpClient.ConnectionState.DISCONNECTED)
        assertFalse(session.settleStatus(old))
        assertFalse(session.canUseConnection)
        assertFalse(SwiggyConnectionSession().canUseConnection)
    }
}
