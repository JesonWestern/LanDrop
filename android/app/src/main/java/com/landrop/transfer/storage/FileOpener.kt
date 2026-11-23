package com.landrop.transfer.storage

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.landrop.transfer.model.MimeTypes
import java.io.File

/** 可选的「打开方式」条目。 */
data class OpenChoice(
    val label: String,
    val packageName: String,
    val className: String,
)

/** 打开文件的结果，调用方负责把它们变成用户能看懂的提示。 */
sealed interface OpenResult {
    /** 已交给系统（默认应用或唯一可用应用）。 */
    data object Started : OpenResult

    /** 没有应用能处理这个类型。 */
    data object NoApp : OpenResult

    /** 文件不存在/已删除/无法生成 uri。 */
    data object NotExist : OpenResult

    /** APK 需要「安装未知应用」授权。 */
    data object NeedInstallPermission : OpenResult

    /** 有多个应用可选：由 UI 弹自己的选择框（避免系统选择器把本应用列进去造成自环）。 */
    data class NeedsChoice(val intent: Intent, val choices: List<OpenChoice>) : OpenResult

    data class Failed(val message: String) : OpenResult
}

/**
 * 「用系统默认应用打开」的统一入口。
 *
 * 一律走 content://（FileProvider / MediaStore uri），targetSdk 36 下不允许 file://。
 * 默认应用优先：只有没有默认处理者、或默认处理者恰好是本应用时，才退化为选择器，
 * 并在选择器里排除自己，避免「打开文件 → 又回到本应用的分享弹窗」。
 */
object FileOpener {

    const val AUTHORITY_SUFFIX = ".fileprovider"
    private const val TAG = "LanDropOpen"

    fun authority(ctx: Context): String = ctx.packageName + AUTHORITY_SUFFIX

    fun mimeOf(name: String, known: String? = null): String =
        MimeTypes.of(name, known) { ext ->
            runCatching { MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) }.getOrNull()
        }

    fun uriOf(ctx: Context, path: String): Uri? = runCatching {
        FileProvider.getUriForFile(ctx, authority(ctx), File(path))
    }.getOrNull()

    /**
     * @param path 本地绝对路径（可为空，此时必须有 mediaUri）
     * @param mediaUri 接收文件落盘后 MediaStore 返回的 content uri
     * @param knownMime 来源已知 MIME（可空）
     */
    fun open(
        ctx: Context,
        path: String,
        knownMime: String? = null,
        mediaUri: String? = null,
    ): OpenResult {
        val name = path.substringAfterLast('/').ifEmpty {
            runCatching { Uri.parse(mediaUri).lastPathSegment }.getOrNull().orEmpty()
        }
        val uri: Uri
        if (!mediaUri.isNullOrBlank()) {
            uri = runCatching { Uri.parse(mediaUri) }.getOrElse { return OpenResult.Failed("文件地址无效") }
        } else {
            if (path.isEmpty()) return OpenResult.NotExist
            val f = File(path)
            if (!f.exists() || f.isDirectory) return OpenResult.NotExist
            uri = uriOf(ctx, path) ?: return OpenResult.Failed("无法生成文件访问地址")
        }
        val mime = mimeOf(name, knownMime)

        if (mime == "application/vnd.android.package-archive" && !canInstall(ctx)) {
            return OpenResult.NeedInstallPermission
        }

        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        view.clipData = ClipData.newRawUri(name.ifEmpty { "file" }, uri)
        val pm = ctx.packageManager
        val resolved = runCatching { pm.resolveActivity(view, PackageManager.MATCH_DEFAULT_ONLY) }.getOrNull()
        val selfPkg = ctx.packageName
        return try {
            if (resolved != null && resolved.activityInfo?.packageName != selfPkg && !isSystemResolver(resolved)) {
                Log.i(TAG, "open $name mime=$mime -> default ${resolved.activityInfo?.packageName}")
                start(ctx, view)
                OpenResult.Started
            } else {
                // 没有默认应用/系统把本应用或系统选择器当成解析结果：只列其他应用，
                // 避免「打开又回到本应用的分享弹窗」，也避免 MIUI 选择器把本应用列进去。
                val choices = otherHandlers(ctx, view)
                Log.i(TAG, "open $name mime=$mime resolved=${resolved?.activityInfo?.name} choices=${choices.size}")
                when {
                    choices.isEmpty() -> OpenResult.NoApp
                    choices.size == 1 -> {
                        start(ctx, explicitIntent(view, choices[0]))
                        OpenResult.Started
                    }
                    else -> OpenResult.NeedsChoice(view, choices)
                }
            }
        } catch (_: ActivityNotFoundException) {
            OpenResult.NoApp
        } catch (e: SecurityException) {
            if (mime == "application/vnd.android.package-archive") OpenResult.NeedInstallPermission
            else OpenResult.Failed(e.message ?: "没有权限打开该文件")
        } catch (e: Exception) {
            OpenResult.Failed(e.message ?: "打开失败")
        }
    }

    /**
     * 系统「选择器」本身也会被 resolveActivity 返回（AOSP: com.android.internal.app.ResolverActivity /
     * com.android.intentresolver，MIUI: …MiuiResolverActivity）。它不是真正的默认应用，必须走我们自己的选择框。
     */
    private fun isSystemResolver(ri: ResolveInfo): Boolean {
        val ai = ri.activityInfo ?: return false
        val pkg = ai.packageName.lowercase()
        val cls = ai.name.lowercase()
        if (pkg == "android" || pkg == "com.android.intentresolver") return true
        return cls.contains("resolver") || cls.contains("chooser")
    }

    /** 其他能处理该 Intent 的应用（按包名去重，排除本应用）。 */
    fun otherHandlers(ctx: Context, view: Intent): List<OpenChoice> {
        val pm = ctx.packageManager
        val list = runCatching {
            pm.queryIntentActivities(view, PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrDefault(emptyList())
        val selfPkg = ctx.packageName
        val seen = HashSet<String>()
        val out = ArrayList<OpenChoice>()
        for (ri in list) {
            val ai = ri.activityInfo ?: continue
            if (ai.packageName == selfPkg) continue
            if (!seen.add(ai.packageName)) continue
            val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault("")
            out.add(
                OpenChoice(
                    label = label.ifBlank { ai.packageName },
                    packageName = ai.packageName,
                    className = ai.name,
                )
            )
        }
        return out.sortedBy { it.label }
    }

    fun explicitIntent(view: Intent, choice: OpenChoice): Intent =
        Intent(view).setComponent(ComponentName(choice.packageName, choice.className))

    private fun start(ctx: Context, intent: Intent) {
        if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    fun canInstall(ctx: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }.getOrDefault(false)

    /** 「安装未知应用」授权页（返回后用户需再点一次文件）。 */
    fun installSettingsIntent(ctx: Context): Intent? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${ctx.packageName}"),
            )
        } else {
            null
        }
    }.getOrNull()
}
