package com.landrop.transfer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.landrop.transfer.core.Engine
import com.landrop.transfer.ui.LanDropApp
import com.landrop.transfer.ui.LanDropTheme

class MainActivity : ComponentActivity() {

    private val notiPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Engine.init(applicationContext)
        Engine.start(applicationContext)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notiPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            LanDropTheme {
                LanDropApp()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 保证服务在跑（用户可能从最近任务里回来）
        runCatching { com.landrop.transfer.service.TransferService.start(this) }
    }
}
