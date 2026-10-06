package com.teja.bumblebee.ui

import android.os.Bundle
import android.view.KeyEvent
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import com.teja.bumblebee.R
import com.teja.bumblebee.ui.diagnostics.DiagnosticsFragment
import com.teja.bumblebee.ui.diagnostics.KeyLog

class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this).apply { id = R.id.content_root })
        Immersive.apply(this)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.content_root, DiagnosticsFragment())
                .commit()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Immersive.apply(this)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            KeyLog.add("Activity key: ${KeyEvent.keyCodeToString(event.keyCode)} (code ${event.keyCode}, repeat ${event.repeatCount})")
        }
        return super.dispatchKeyEvent(event)
    }
}
