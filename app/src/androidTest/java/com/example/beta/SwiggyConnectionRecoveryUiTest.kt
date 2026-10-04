package com.example.beta

import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Injected read outcomes only: never starts OAuth, searches or changes a cart. */
@RunWith(AndroidJUnit4::class)
class SwiggyConnectionRecoveryUiTest {
    @Test fun transientStatusFailuresKeepDraftEditableAndOfferRetryNotLogin() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var originalComposerText: String? = null
            scenario.onActivity { activity ->
                originalComposerText = activity.findViewById<EditText>(R.id.orderCommandInput).text.toString()
            }
            try {
                for (code in listOf(null, 429, 500, 502, 401, 403)) {
                    scenario.onActivity { activity ->
                        val sessionField = MainActivity::class.java.getDeclaredField("connectionSession").apply { isAccessible = true }
                        val session = sessionField.get(activity) as SwiggyConnectionSession
                        session.invalidate() // Ignore the Activity's real startup GET, if still outstanding.
                        val update = MainActivity::class.java.getDeclaredMethod("updateSwiggyConnectionUi", SwiggyMcpClient.ConnectionState::class.java, String::class.java).apply { isAccessible = true }
                        update.invoke(activity, SwiggyMcpClient.ConnectionState.READY, null)
                        val input = activity.findViewById<EditText>(R.id.orderCommandInput)
                        input.setText("milk tetra pack, dark chocolate")
                        val fail = MainActivity::class.java.getDeclaredMethod("showSwiggyConnectionFailure", SwiggyMcpClient.SwiggyMcpResult.Failure::class.java).apply { isAccessible = true }
                        fail.invoke(activity, SwiggyMcpClient.SwiggyMcpResult.Failure("Temporary test failure", httpCode = code))
                        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.orderComposerCard).visibility)
                        assertEquals("Try connection again", activity.findViewById<TextView>(R.id.swiggyConnectionAction).text.toString())
                        assertEquals("milk tetra pack, dark chocolate", input.text.toString())
                        assertTrue(input.isEnabled)
                        assertFalse(session.canUseConnection)
                        input.setText("edited list")
                        val pending = MainActivity::class.java.getDeclaredField("pendingSwiggyInstruction").apply { isAccessible = true }
                        assertNull(pending.get(activity))
                    }
                }
            } finally {
                val draft = originalComposerText
                if (draft != null) {
                    scenario.onActivity { activity ->
                        activity.findViewById<EditText>(R.id.orderCommandInput).setText(draft)
                    }
                }
            }
        }
    }

    @Test fun explicitReconnectStillKeepsLocalEditingAvailable() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val session = MainActivity::class.java.getDeclaredField("connectionSession").apply { isAccessible = true }.get(activity) as SwiggyConnectionSession
                session.invalidate()
                val fail = MainActivity::class.java.getDeclaredMethod("showSwiggyConnectionFailure", SwiggyMcpClient.SwiggyMcpResult.Failure::class.java).apply { isAccessible = true }
                fail.invoke(activity, SwiggyMcpClient.SwiggyMcpResult.Failure("Reconnect needed", reconnectRequired = true))
                assertEquals(activity.getString(R.string.swiggy_connection_reconnect_action), activity.findViewById<TextView>(R.id.swiggyConnectionAction).text.toString())
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.orderComposerCard).visibility)
                assertFalse(session.canUseConnection)
            }
        }
    }
}
