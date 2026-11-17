package org.ledfxmobile.app.service

import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** Shows Android's "allow capturing audio from other apps" dialog and hands the result on. */
class CaptureConsentActivity : ComponentActivity() {

    private val consent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val data = res.data
            if (res.resultCode == Activity.RESULT_OK && data != null) {
                EngineService.deliverConsent(this, res.resultCode, data)
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            consent.launch(mpm.createScreenCaptureIntent())
        }
    }
}
