package com.kiran.agentskills.sdk.host

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.kiran.agentskills.sdk.SkillContract

/** A skill provider found on the device, described from its manifest without binding. */
data class SkillProviderInfo(
    val component: ComponentName,
    val id: String,
    val title: String,
    val summary: String,
    val keywords: List<String>,
    /** The provider app's version, used to invalidate cached descriptors after an update. */
    val versionCode: Long,
)

/**
 * Finds installed skill providers. The host app needs, in its manifest:
 * ```
 * <uses-permission android:name="com.kiran.agentskills.permission.SKILL" />
 * <queries><intent><action android:name="com.kiran.agentskills.action.SKILL_PROVIDER" /></intent></queries>
 * ```
 * Only services guarded by [SkillContract.PERMISSION] are returned, so a provider can't skip the
 * signature check by leaving its service unprotected.
 */
class SkillDirectory(context: Context) {

    private val pm = context.packageManager

    fun discover(): List<SkillProviderInfo> {
        val intent = Intent(SkillContract.ACTION_SKILL_PROVIDER)
        val found = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentServices(intent, PackageManager.GET_META_DATA)
        }
        return found.mapNotNull { ri ->
            val si = ri.serviceInfo ?: return@mapNotNull null
            if (si.permission != SkillContract.PERMISSION || !si.exported) return@mapNotNull null
            val meta = si.metaData
            val id = meta?.getString(SkillContract.META_ID) ?: return@mapNotNull null
            SkillProviderInfo(
                component = ComponentName(si.packageName, si.name),
                id = id,
                title = meta.getString(SkillContract.META_TITLE) ?: id,
                summary = meta.getString(SkillContract.META_SUMMARY).orEmpty(),
                keywords = meta.getString(SkillContract.META_KEYWORDS).orEmpty()
                    .split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() },
                versionCode = versionOf(si.packageName),
            )
        }
    }

    private fun versionOf(pkg: String): Long = runCatching {
        val info = pm.getPackageInfo(pkg, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }.getOrDefault(0L)
}
