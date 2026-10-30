package de.needanapp.needafile.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import de.needanapp.needafile.home.HomeActivity

/**
 * The app's settings link in the system settings. Passes on to the main screen, also when it is already open: there the
 * system would only bring the task to the front without handing over the request.
 */
class SettingsLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, HomeActivity::class.java)
                .putExtra(HomeActivity.EXTRA_OPEN_SETTINGS, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
