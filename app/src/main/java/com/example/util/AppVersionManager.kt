package com.example.util

import android.content.Context
import android.os.Build
import com.example.BuildConfig

/**
 * Represents the installed or allocated APK release version metadata.
 */
data class InstalledAppVersion(
    val versionName: String,
    val versionCode: Int,
    val nextReleaseVersionName: String = AppVersionManager.incrementPatchVersion(versionName),
    val nextReleaseVersionCode: Int = (versionCode + 1).coerceAtLeast(AppVersionManager.MIN_MONOTONIC_VERSION_CODE + 1)
) {
    fun formatDisplay(isHebrew: Boolean, includeVersionCode: Boolean = true): String {
        return if (includeVersionCode) {
            if (isHebrew) {
                "גרסה $versionName (קוד בנייה $versionCode)"
            } else {
                "Version $versionName (Build $versionCode)"
            }
        } else {
            versionName
        }
    }
}

/**
 * Represents a published Git tag / GitHub Release record used by GitHub Actions
 * to track released versions and guarantee idempotency for repeated builds of the same commit.
 */
data class ReleasedTagRecord(
    val versionName: String,
    val versionCode: Int,
    val commitSha: String
)

/**
 * Result of computing the version for a candidate APK release build.
 */
data class ReleaseVersionAllocation(
    val versionName: String,
    val versionCode: Int,
    val commitSha: String,
    val isAlreadyReleasedForCommit: Boolean,
    val releaseTag: String = "v$versionName-vc$versionCode"
)

/**
 * Manages FamilyTasks semantic release versioning (baseline `1.0.0`, automatic PATCH increments
 * `1.0.0` -> `1.0.1` -> ... -> `1.0.9` -> `1.0.10`, and monotonically increasing `versionCode >= 7`).
 */
object AppVersionManager {

    const val BASELINE_VERSION_NAME = "1.0.0"
    const val LEGACY_V16_VERSION_CODE = 6
    const val MIN_MONOTONIC_VERSION_CODE = 7

    val displayVersionLabel: String
        get() = "V${BuildConfig.VERSION_NAME}"

    val currentVersionCode: Int
        get() = BuildConfig.VERSION_CODE

    private val SEMVER_REGEX = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-vc(\d+)|\+code\.(\d+))?$""")

    /**
     * Reads the actual installed package version metadata from Android's [android.content.pm.PackageManager],
     * falling back safely to [BuildConfig] only if PackageManager lookup is unavailable.
     */
    fun getInstalledVersion(context: Context?): InstalledAppVersion {
        if (context != null) {
            runCatching {
                val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                val rawName = pkgInfo.versionName?.trim()?.takeIf { it.isNotEmpty() }
                    ?: BuildConfig.VERSION_NAME
                val rawCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pkgInfo.longVersionCode.toInt()
                } else {
                    @Suppress("DEPRECATION")
                    pkgInfo.versionCode
                }
                val safeCode = if (rawCode > 0) rawCode else BuildConfig.VERSION_CODE
                return InstalledAppVersion(
                    versionName = rawName,
                    versionCode = safeCode
                )
            }
        }
        return InstalledAppVersion(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE
        )
    }

    /**
     * Parses a semantic version string (`1.0.0`, `1.0.10`, `v1.0.3-vc10`) into `(major, minor, patch)`.
     */
    fun parseSemVer(versionName: String): Triple<Int, Int, Int>? {
        val clean = versionName.trim()
        val match = SEMVER_REGEX.matchEntire(clean) ?: return null
        val major = match.groupValues[1].toIntOrNull() ?: return null
        val minor = match.groupValues[2].toIntOrNull() ?: return null
        val patch = match.groupValues[3].toIntOrNull() ?: return null
        return Triple(major, minor, patch)
    }

    /**
     * Increments ONLY the PATCH component of a semantic version (`1.0.0` -> `1.0.1`, `1.0.9` -> `1.0.10`).
     */
    fun incrementPatchVersion(currentVersionName: String): String {
        val parsed = parseSemVer(currentVersionName) ?: return "1.0.1"
        val (major, minor, patch) = parsed
        return "$major.$minor.${patch + 1}"
    }

    /**
     * Parses a Git release tag such as `v1.0.0-vc7` or `v1.0.12+code.19` into `(versionName, versionCode)`.
     */
    fun parseReleaseTag(tag: String, commitSha: String = ""): ReleasedTagRecord? {
        val match = SEMVER_REGEX.matchEntire(tag.trim()) ?: return null
        val major = match.groupValues[1].toIntOrNull() ?: return null
        val minor = match.groupValues[2].toIntOrNull() ?: return null
        val patch = match.groupValues[3].toIntOrNull() ?: return null
        val codeFromVc = match.groupValues[4].takeIf { it.isNotEmpty() }?.toIntOrNull()
        val codeFromPlus = match.groupValues[5].takeIf { it.isNotEmpty() }?.toIntOrNull()
        val code = (codeFromVc ?: codeFromPlus ?: (MIN_MONOTONIC_VERSION_CODE + patch))
            .coerceAtLeast(MIN_MONOTONIC_VERSION_CODE)
        return ReleasedTagRecord(
            versionName = "$major.$minor.$patch",
            versionCode = code,
            commitSha = commitSha.trim()
        )
    }

    /**
     * Computes the authoritative release version for [candidateCommitSha] given previously published
     * [existingReleases].
     *
     * Rules enforced:
     * 1. If [candidateCommitSha] is already associated with a published release, returns that exact
     *    version with `isAlreadyReleasedForCommit = true` (idempotent rebuild; no patch bump).
     * 2. If no `1.0.x` release exists yet, allocates `versionName = "1.0.0"` and `versionCode = 7`
     *    (strictly greater than V1.6's `versionCode = 6`).
     * 3. For every subsequent new release commit, increments PATCH by +1 (`1.0.0` -> `1.0.1` -> ... -> `1.0.10`)
     *    and increments `versionCode` monotonically (`maxVersionCode + 1`).
     */
    fun allocateReleaseVersion(
        candidateCommitSha: String,
        existingReleases: List<ReleasedTagRecord>
    ): ReleaseVersionAllocation {
        val cleanSha = candidateCommitSha.trim()
        val existingForCommit = existingReleases.firstOrNull {
            cleanSha.isNotEmpty() && it.commitSha.equals(cleanSha, ignoreCase = true)
        }
        if (existingForCommit != null) {
            return ReleaseVersionAllocation(
                versionName = existingForCommit.versionName,
                versionCode = existingForCommit.versionCode.coerceAtLeast(MIN_MONOTONIC_VERSION_CODE),
                commitSha = cleanSha,
                isAlreadyReleasedForCommit = true
            )
        }

        val validSemverReleases = existingReleases.mapNotNull { rec ->
            val triple = parseSemVer(rec.versionName) ?: return@mapNotNull null
            Pair(triple, rec)
        }

        if (validSemverReleases.isEmpty()) {
            return ReleaseVersionAllocation(
                versionName = BASELINE_VERSION_NAME,
                versionCode = MIN_MONOTONIC_VERSION_CODE,
                commitSha = cleanSha,
                isAlreadyReleasedForCommit = false
            )
        }

        val highestPatchRelease = validSemverReleases.maxWithOrNull(
            compareBy<Pair<Triple<Int, Int, Int>, ReleasedTagRecord>> { it.first.first }
                .thenBy { it.first.second }
                .thenBy { it.first.third }
        )!!

        val (major, minor, maxPatch) = highestPatchRelease.first
        val maxExistingCode = existingReleases.maxOfOrNull { it.versionCode } ?: LEGACY_V16_VERSION_CODE
        val nextVersionName = "$major.$minor.${maxPatch + 1}"
        val nextVersionCode = (maxExistingCode + 1).coerceAtLeast(MIN_MONOTONIC_VERSION_CODE)

        return ReleaseVersionAllocation(
            versionName = nextVersionName,
            versionCode = nextVersionCode,
            commitSha = cleanSha,
            isAlreadyReleasedForCommit = false
        )
    }
}
