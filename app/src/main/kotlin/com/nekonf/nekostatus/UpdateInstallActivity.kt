package com.nekonf.nekostatus

import android.app.Activity
import android.os.Bundle

class UpdateInstallActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UpdateManager.installReadyUpdate(this)
        finish()
    }
}
