package com.landrop.transfer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.landrop.transfer.core.Engine
import com.landrop.transfer.share.ShareIntake
import com.landrop.transfer.storage.Catalog
import com.landrop.transfer.storage.FileRepo
import com.landrop.transfer.ui.LanDropApp
import com.landrop.transfer.ui.LanDropTheme
import com.landrop.transfer.ui.Motion
import com.landrop.transfer.ui.ThemeStore
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val notiPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Android 8-10（API 26-29）没有「全部文件访问」，用 READ/WRITE 运行时权限浏览与落盘。 */
    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Engine.init(applicationContext)
        Engine.start(applicationContext)

        // 主题要在 setContent 之前就已知晓，用来给 window 设底色 —— 否则冷启动第一帧是系统白/黑，
        // 换主题后尤其明显（纯白主题会白闪，纯黑主题会黑闪）。
        ThemeStore.init(applicationContext)
        runCatching {
            window.setBackgroundDrawable(ColorDrawable(ThemeStore.peekBg(this).toArgb()))
        }
        FileRepo.warm(applicationContext)
        Catalog.warm(applicationContext)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notiPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) requestLegacyStoragePermissions()

        // 冷启动：可能是从别的应用「分享 / 打开方式」进来的
        ShareIntake.offer(intent, applicationContext)

        setContent {
            LanDropTheme {
                LanDropApp()
            }
        }
    }

    /** 热启动：singleTop 下再次被分享会走这里，必须更新 intent 并投递。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ShareIntake.offer(intent, applicationContext)
    }

    override fun onResume() {
        super.onResume()
        // 用户可能刚从系统设置里改了"动画时长缩放"，丢掉缓存重新判断
        Motion.invalidate()
        // 保证服务在跑（用户可能从最近任务里回来）
        runCatching { com.landrop.transfer.service.TransferService.start(this) }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) requestLegacyStoragePermissions()
    }

    private fun requestLegacyStoragePermissions() {
        val perms = listOfNotNull(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            } else {
                null
            },
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (perms.isNotEmpty()) runCatching { storagePermission.launch(perms.toTypedArray()) }
    }
}
