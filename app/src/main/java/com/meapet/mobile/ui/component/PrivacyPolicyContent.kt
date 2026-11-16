package com.meapet.mobile.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meapet.mobile.BuildConfig
import com.meapet.mobile.R
import com.meapet.mobile.core.AppInfo

// ═══════════════════════════════════════════════════
//  隐私政策版本与日期（两份政策共享同一套）
// ═══════════════════════════════════════════════════
// 硬编码在此而非 local.properties：修订政策必然改动本文件文案，版本号/日期随代码一起
// 提交，避免放配置里时漏改。修订时必须同时递增版本号并更新日期（与 MainActivity 的
// 版本比对逻辑配合：版本号变化 → 老用户重新弹窗确认）。
const val PRIVACY_POLICY_VERSION = "1.2"
const val PRIVACY_POLICY_EFFECTIVE_DATE = "2026-07-29"
const val PRIVACY_POLICY_UPDATE_DATE = "2026-08-31"

/**
 * 共享的隐私政策正文内容（纯内容，不含任何外壳组件）。
 *
 * 供 [PrivacyDialog] 和设置页的隐私政策子页复用，确保两处文案一致且由同一模块维护。
 *
 * ## 两份政策分开维护（同一文件）
 * 按构建是否包含统计 SDK（[BuildConfig.UMENG_ENABLED]）分派到两套完整政策：
 * - [PrivacyPolicyWithUmeng]：集成友盟+ 统计 SDK 的构建，说明采集范围、授权管理与数据安全；
 * - [PrivacyPolicyWithoutSdk]：无统计 SDK 的构建，声明不采集任何数据。
 * 两份政策各自成篇、章节独立编号，修订时分别维护；版本号/日期由上方共享常量统一提供。
 */
@Composable
fun PrivacyPolicyContent() {
    if (BuildConfig.UMENG_ENABLED) {
        PrivacyPolicyWithUmeng()
    } else {
        PrivacyPolicyWithoutSdk()
    }
}

/** 含友盟+ 统计 SDK 构建的隐私政策全文（与无 SDK 版分开维护）。 */
@Composable
private fun PrivacyPolicyWithUmeng() {
    PrivacyHeader()

    PrivacySection(stringResource(R.string.policy_sec_overview)) {
        Text(
            stringResource(R.string.policy_overview_umeng, AppInfo.devName),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    PrivacySection(stringResource(R.string.policy_sec_collected)) {
        PrivacyBullet(stringResource(R.string.policy_collected_1))
        PrivacyBullet(stringResource(R.string.policy_collected_2))
        PrivacyBullet(stringResource(R.string.policy_collected_3))
        PrivacyBullet(stringResource(R.string.policy_collected_4))
    }

    PrivacySection(stringResource(R.string.policy_sec_purpose)) {
        PrivacyBullet(stringResource(R.string.policy_purpose_1))
        PrivacyBullet(stringResource(R.string.policy_purpose_2))
        PrivacyBullet(stringResource(R.string.policy_purpose_3))
    }

    PrivacySection(stringResource(R.string.policy_sec_sdk)) {
        Text(
            stringResource(R.string.policy_sdk_name),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            stringResource(R.string.policy_sdk_type),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            stringResource(R.string.policy_sdk_data),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            stringResource(R.string.policy_sdk_link),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LinkItem(
            text = AppInfo.umengPolicyUrl,
            url = AppInfo.umengPolicyUrl,
            uriHandler = LocalUriHandler.current
        )
    }

    PrivacySection(stringResource(R.string.policy_sec_consent)) {
        PrivacyBullet(stringResource(R.string.policy_consent_1))
        PrivacyBullet(stringResource(R.string.policy_consent_2))
        PrivacyBullet(stringResource(R.string.policy_consent_3))
        PrivacyBullet(stringResource(R.string.policy_consent_4))
    }

    PrivacySection(stringResource(R.string.policy_sec_security)) {
        Text(
            stringResource(R.string.policy_security_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    PrivacySection(stringResource(R.string.policy_sec_rights)) {
        PrivacyBullet(stringResource(R.string.policy_rights_1))
        PrivacyBullet(stringResource(R.string.policy_rights_2))
        PrivacyBullet(stringResource(R.string.policy_rights_3))
    }

    PrivacySection(stringResource(R.string.policy_sec_contact_umeng)) {
        Text(
            stringResource(R.string.policy_contact_body, AppInfo.devName, AppInfo.gitRepoUrl),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 无统计 SDK 构建的隐私政策全文（与含 SDK 版分开维护）。 */
@Composable
private fun PrivacyPolicyWithoutSdk() {
    PrivacyHeader()

    PrivacySection(stringResource(R.string.policy_sec_overview)) {
        Text(
            stringResource(R.string.policy_overview_nosdk, AppInfo.devName),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    PrivacySection(stringResource(R.string.policy_sec_processing)) {
        PrivacyBullet(stringResource(R.string.policy_nosdk_1))
        PrivacyBullet(stringResource(R.string.policy_nosdk_2))
    }

    PrivacySection(stringResource(R.string.policy_sec_rights_nosdk)) {
        PrivacyBullet(stringResource(R.string.policy_rights_3))
    }

    PrivacySection(stringResource(R.string.policy_sec_contact_nosdk)) {
        Text(
            stringResource(R.string.policy_contact_body, AppInfo.devName, AppInfo.gitRepoUrl),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 隐私政策头部：版本、生效时间与修订时间（两份政策共享 [PRIVACY_POLICY_VERSION] 等常量）。
 */
@Composable
private fun PrivacyHeader() {
    Column(
        modifier = Modifier.padding(bottom = 8.dp)
    ) {
        Text(
            stringResource(R.string.policy_version_label, PRIVACY_POLICY_VERSION),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            stringResource(R.string.policy_effective_label, PRIVACY_POLICY_EFFECTIVE_DATE),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            stringResource(R.string.policy_updated_label, PRIVACY_POLICY_UPDATE_DATE),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 隐私政策小节标题。
 */
@Composable
private fun PrivacySection(
    title: String,
    content: @Composable () -> Unit
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
    )
    content()
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
        modifier = Modifier.padding(top = 12.dp)
    )
}

/**
 * 隐私政策 bullet 条目。
 */
@Composable
private fun PrivacyBullet(text: String) {
    Text(
        text = "• $text",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 2.dp)
    )
}
