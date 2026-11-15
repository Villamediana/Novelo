package com.villamediana.novelo.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.villamediana.novelo.NoveloApplication
import com.villamediana.novelo.local.LocalModel
import com.villamediana.novelo.settings.Job
import com.villamediana.novelo.settings.NoveloSettings
import com.villamediana.novelo.settings.Source
import com.villamediana.novelo.settings.ThemeMode
import com.villamediana.novelo.ui.home.Missing
import com.villamediana.novelo.ui.home.missing
import com.villamediana.novelo.ui.settings.intelligenceShown
import com.villamediana.novelo.ui.settings.intelligenceSummary
import com.villamediana.novelo.ui.setup.SetupScreen
import com.villamediana.novelo.ui.theme.NoveloTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Novelo with its own intelligence built in: nothing to choose, nothing to download. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EmbeddedTest {
    @get:Rule val compose = createComposeRule()
    private val graph get() = NoveloApplication.graph

    @Test
    fun theFirstStepsAreThePermissionsAndTheBackup() {
        runBlocking { graph.settings.setBackupFolder("", "") }
        var done = false
        compose.setContent { NoveloTheme(ThemeMode.LIGHT) { SetupScreen(onDone = { done = true }, choose = false) } }
        try {
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Microfone").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Agora não").performClick()
            // Straight to the backup: nobody is asked who hears, who sees, who thinks; nothing to download.
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Onde guardar seu backup?").fetchSemanticsNodes().isNotEmpty() }
            for (asked in listOf("Quem vai ouvir você?", "O ouvido do Novelo", "Sua própria API", "Baixar")) {
                assertTrue(asked, compose.onAllNodesWithText(asked).fetchSemanticsNodes().isEmpty())
            }
            // A backup is still a must; with it, the app starts.
            assertTrue(compose.onAllNodesWithText("Começar").fetchSemanticsNodes().isEmpty())
            runBlocking { graph.settings.setBackupFolder("content://com.android.externalstorage.documents/tree/primary%3ADocuments", "Documents/Novelo backup.zip") }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Começar").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Começar").performClick()
            compose.waitUntil(5_000) { done }
            assertTrue(runBlocking { graph.settings.current().setupDone })
        } finally {
            runBlocking { graph.settings.setBackupFolder("", "") }
        }
    }

    @Test
    fun whoIsOnTheNovelosIntelligenceSeesNoChoicesUntilSheNeedsThem() {
        val all = NoveloSettings(hear = Source.NOVELO, see = Source.NOVELO, think = Source.NOVELO)
        // All on the Novelo's, and it answers: nothing to show, nothing missing.
        assertFalse(intelligenceShown(all, novelo = true))
        assertTrue(missing(all, LocalModel.State.Missing, engine = true, novelo = true).isEmpty())
        assertFalse(intelligenceSummary(all, LocalModel.State.Missing, novelo = true).second)
        // The server said it is over for this phone (the trial ended): said on the button, and the choices show.
        assertEquals(listOf(Missing.NOVELO), missing(all, LocalModel.State.Missing, engine = true, novelo = false))
        assertTrue(missing(all, LocalModel.State.Missing, engine = true, novelo = false).single().locks)
        assertTrue(intelligenceShown(all, novelo = false))
        assertTrue(intelligenceSummary(all, LocalModel.State.Missing, novelo = false).second)
        // She goes on with the model on the phone and her API: what those need is what is missing, as before.
        val own = all.copy(hear = Source.LOCAL, see = Source.LOCAL, think = Source.API, apiProvider = "openai", hasApiKey = true, thinkModel = "gpt")
        assertEquals(listOf(Missing.EARS), missing(own, LocalModel.State.Missing, engine = true, novelo = false))
        assertTrue(missing(own, LocalModel.State.Ready, engine = true, novelo = false).isEmpty())
        assertTrue(intelligenceShown(own, novelo = true))
        // Only who sees is the Novelo's and it is over: said, without locking the button.
        val sees = own.copy(see = Source.NOVELO)
        assertEquals(listOf(Missing.SEE), missing(sees, LocalModel.State.Ready, engine = true, novelo = false))
        // Nothing on the phone thinks: "Local" for who thinks asks for her API.
        assertEquals(listOf(Missing.THINK_API), missing(all.copy(think = Source.LOCAL), LocalModel.State.Ready, engine = true, novelo = true))
        assertEquals(Job.THINK, Job.valueOf("THINK"))
    }

    @Test
    fun whoChoseHerApiStaysOnItAndTheRestIsTheNovelosNow() {
        // Before the Novelo had its own intelligence, "local" was the Novelo's (ears on the phone, free models online).
        assertEquals(Source.NOVELO, Source.of(null, "local"))
        assertEquals(Source.NOVELO, Source.of(null, null))
        assertEquals(Source.API, Source.of(null, "api"))
        // In a build without the server, who hears and sees is the model on the phone.
        assertEquals(Source.LOCAL, Source.of(null, "local", Source.LOCAL))
        assertEquals(Source.API, Source.of(null, "api", Source.LOCAL))
        // What she chooses from now on is what counts.
        assertEquals(Source.LOCAL, Source.of("local", "api"))
        assertEquals(Source.NOVELO, Source.of("novelo", "api"))
        assertEquals(Source.API, Source.of("api", "local"))
    }
}
