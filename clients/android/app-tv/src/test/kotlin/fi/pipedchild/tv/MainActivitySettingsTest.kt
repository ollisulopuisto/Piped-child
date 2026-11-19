package fi.pipedchild.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercise the real MainActivity entry and settings-result wiring without
 * granting API consent, so the test never contacts a real instance. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivitySettingsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun `a changed instance result restarts MainActivity with the new approval removed`() {
        compose.onNodeWithText("Continue").assertIsDisplayed().assertIsFocused()
        compose.onNodeWithText("Instance settings").assertIsDisplayed()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file = File("build/tv-screens/main-entry.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        compose.onNodeWithText("Instance settings").performClick()
        compose.runOnIdle {
            val launched = shadowOf(compose.activity).nextStartedActivityForResult
            val originalLaunch = shadowOf(compose.activity).nextStartedActivity
            assertEquals(InstanceSettingsActivity::class.java.name, originalLaunch.component!!.className)
            assertEquals(InstanceSettingsActivity::class.java.name, launched.intent.component!!.className)
            assertTrue(InstancePreferences(compose.activity).save("https://new.example"))
            compose.activity.activityResultRegistry.dispatchResult(launched.requestCode, Activity.RESULT_OK, Intent())
            assertTrue(compose.activity.isFinishing)
            val fresh = shadowOf(compose.activity).nextStartedActivity
            assertEquals(MainActivity::class.java.name, fresh.component!!.className)
            val prefs = compose.activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
            assertNull(prefs.getString(KEY_CONSENTED_INSTANCE, null))
        }
    }

    @Test fun `settings is reachable by remote before consent and Cancel preserves entry`() {
        compose.onNodeWithText("Continue").assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithText("Instance settings").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle {
            val launched = shadowOf(compose.activity).nextStartedActivityForResult
            val originalLaunch = shadowOf(compose.activity).nextStartedActivity
            assertEquals(InstanceSettingsActivity::class.java.name, originalLaunch.component!!.className)
            assertEquals(InstanceSettingsActivity::class.java.name, launched.intent.component!!.className)
            compose.activity.activityResultRegistry.dispatchResult(launched.requestCode, Activity.RESULT_CANCELED, Intent())
            assertFalse(compose.activity.isFinishing)
            assertNull(shadowOf(compose.activity).nextStartedActivity)
            assertNull(compose.activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                .getString(KEY_CONSENTED_INSTANCE, null))
        }
        compose.onNodeWithText("Instance settings").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Continue").assertIsFocused()
    }

    @Test fun `recreation before consent stays gated and restores remote focus`() {
        compose.onNodeWithText("Continue").assertIsFocused()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithText("Continue").assertIsDisplayed().assertIsFocused()
        compose.onNodeWithText("Instance settings").assertIsDisplayed()
        compose.runOnIdle {
            assertNull(compose.activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                .getString(KEY_CONSENTED_INSTANCE, null))
        }
    }

    @Test fun `unchanged successful settings result does not restart or grant approval`() {
        compose.onNodeWithText("Instance settings").performClick()
        compose.runOnIdle {
            val launched = shadowOf(compose.activity).nextStartedActivityForResult
            val originalLaunch = shadowOf(compose.activity).nextStartedActivity
            assertEquals(InstanceSettingsActivity::class.java.name, originalLaunch.component!!.className)
            assertTrue(InstancePreferences(compose.activity).save(DEFAULT_INSTANCE))
            compose.activity.activityResultRegistry.dispatchResult(launched.requestCode, Activity.RESULT_OK, Intent())
            assertFalse(compose.activity.isFinishing)
            assertNull(shadowOf(compose.activity).nextStartedActivity)
            assertNull(compose.activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                .getString(KEY_CONSENTED_INSTANCE, null))
        }
        compose.onNodeWithText("Continue").assertIsDisplayed()
    }
}
