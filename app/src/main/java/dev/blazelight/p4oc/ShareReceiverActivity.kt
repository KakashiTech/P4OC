package dev.blazelight.p4oc

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.blazelight.p4oc.core.network.ConnectionManager
import dev.blazelight.p4oc.data.remote.dto.*
import dev.blazelight.p4oc.ui.navigation.Screen
import dev.blazelight.p4oc.ui.tabs.TabManager
import dev.blazelight.p4oc.ui.theme.LocalOpenCodeTheme
import dev.blazelight.p4oc.ui.theme.PocketCodeTheme
import dev.blazelight.p4oc.ui.theme.TuiCodeFontSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class ShareReceiverActivity : ComponentActivity() {

    private val connectionManager: ConnectionManager by inject()
    private val tabManager: TabManager by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!connectionManager.isConnected) {
            Toast.makeText(this, "Conectate a un servidor primero", Toast.LENGTH_SHORT).show()
            finishAndGoToMain()
            return
        }

        val sharedText = extractSharedText(intent)
        if (sharedText == null) {
            Toast.makeText(this, "No hay contenido para compartir", Toast.LENGTH_SHORT).show()
            finishAndGoToMain()
            return
        }

        setContent {
            PocketCodeTheme {
                val theme = LocalOpenCodeTheme.current
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = theme.background.copy(alpha = 0.92f)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator(
                                color = theme.accent,
                                modifier = Modifier.size(32.dp),
                                strokeWidth = 3.dp
                            )
                            Text(
                                text = "enviando a AI...",
                                fontFamily = FontFamily.Monospace,
                                fontSize = TuiCodeFontSize.md,
                                color = theme.textMuted
                            )
                        }
                    }
                }
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val api = connectionManager.requireApi()
                val title = sharedText.take(80).replace("\n", " ").trim()
                val session = api.createSession(
                    directory = null,
                    request = CreateSessionRequest(title = title)
                )
                val sessionId = session.id

                api.initSession(sessionId, InitSessionRequest(messageID = ""))
                api.sendMessageAsync(sessionId, SendMessageRequest(
                    parts = listOf(PartInputDto(type = "text", text = sharedText))
                ))

                tabManager.createTab(
                    startRoute = Screen.Chat.createRoute(sessionId),
                    focus = true
                )

                withContext(Dispatchers.Main) {
                    startActivity(Intent(this@ShareReceiverActivity, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra("share_session_id", sessionId)
                    })
                    finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@ShareReceiverActivity,
                        "Error: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    finishAndGoToMain()
                }
            }
        }
    }

    private fun extractSharedText(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
        if (text.isNullOrEmpty()) return null
        return text
    }

    private fun finishAndGoToMain() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        })
        finish()
    }
}
