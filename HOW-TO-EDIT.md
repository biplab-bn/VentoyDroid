# VentoyDroid — Editing Guide (for beginners)

This guide shows **which file to open** and **exactly what to change** for
every common task. No programming knowledge needed — just follow the lines.

---

## 1. Map of the important files

| I want to change... | Open this file |
|---|---|
| Ad IDs (real ads) | `app/src/main/java/com/ventoydroid/app/ads/Ads.kt` **and** `app/src/main/AndroidManifest.xml` |
| App version number | `app/build.gradle.kts` |
| App name shown on phone | `app/src/main/res/values/strings.xml` (line: `app_name`) |
| Arabic app name | `app/src/main/res/values-ar/strings.xml` |
| Play Store texts | `fastlane/metadata/android/en-US/` (see section 4) |
| Privacy policy | `fastlane/metadata/android/en-US/PRIVACY_POLICY.md` |
| App icon | `app/src/main/res/mipmap-anydpi-v26/` (rebranding — later phase) |
| Ventoy version bundled | `app/src/main/assets/ventoy/` (don't touch unless updating Ventoy) |

---

## 2. Switching to REAL ads (after you make an AdMob account)

**Step 1** — Open `app/src/main/java/com/ventoydroid/app/ads/Ads.kt`.
Near the top you will see:

```kotlin
private const val USE_REAL_IDS = false
...
if (USE_REAL_IDS) "REPLACE_WITH_REAL_BANNER_UNIT_ID"
...
if (USE_REAL_IDS) "REPLACE_WITH_REAL_INTERSTITIAL_UNIT_ID"
```

Change to:

```kotlin
private const val USE_REAL_IDS = true
```
and replace the two `REPLACE_WITH_REAL...` texts with your real ad unit IDs
from admob.google.com (they look like `ca-app-pub-XXXXX/XXXXX`).

**Step 2** — Open `app/src/main/AndroidManifest.xml`. Find:

```xml
android:value="ca-app-pub-3940256099942544~3347511713"
```
Replace with your real **App ID** from AdMob (note: App ID has a `~`,
ad unit IDs have a `/` — don't mix them up).

⚠️ **Warning:** never put real IDs in a version you share as a debug APK —
Google can ban your AdMob account for that. Real IDs only in release builds.

---

## 3. Changing the version (do this for every update)

Open `app/build.gradle.kts`. Find:

```kotlin
versionCode = 13      // ← add +1 every new build (14, 15, 16...)
versionName = "0.3.0" // ← the human-readable number (0.3.1, 0.4.0...)
```

Rules:
- `versionCode` must **always be bigger** than the last one (Play Store requirement)
- `versionName` is what users see — anything you like

---

## 4. Play Store texts

All in `fastlane/metadata/android/en-US/`:

| File | What it is | Limit |
|---|---|---|
| `title.txt` | App name on the store | 30 characters |
| `short_description.txt` | The one-liner | 80 characters |
| `full_description.txt` | The long description | 4000 characters |
| `changelogs/13.txt` | "What's new" for versionCode 13 | 500 characters |

**Important:** the changelog file name must match the versionCode.
If versionCode is now 14, create `changelogs/14.txt` with the new notes.

---

## 5. Privacy policy (needed before Play upload)

The text is ready in `fastlane/metadata/android/en-US/PRIVACY_POLICY.md`.

To put it online free:
1. Make a free account at github.com
2. Create a public repository, upload this file renamed to `privacy-policy.md`
3. Settings → Pages → enable → you get a link like
   `https://YOURNAME.github.io/REPONAME/privacy-policy`
4. Paste that link in Play Console when it asks for "Privacy Policy URL"

---

## 6. Building after any edit

Ask me ("rebuild and install on phone") — or do it yourself:

```bash
# open a terminal in the project folder, then:
export JAVA_HOME=/tmp/android-toolchain/jdk   # note: this temp folder may need re-setup after a PC reboot

./gradlew assembleDebug          # fast test build  → app/build/outputs/apk/debug/
./gradlew assembleRelease        # release APK      → app/build/outputs/apk/release/
./gradlew bundleRelease          # Play Store file  → app/build/outputs/bundle/release/
```

Install on the phone:
```bash
/tmp/android-toolchain/sdk/platform-tools/adb.exe install -r app/build/outputs/apk/release/app-release.apk
```

**If MIUI blocks it** (`INSTALL_FAILED_USER_RESTRICTED`):
1. Settings → Additional settings → Developer options → enable **Install via USB**
2. Or push the file and tap it:
   ```bash
   adb push app-release.apk /sdcard/Download/app.apk
   ```
   then open Files → Download → tap the APK on the phone.

**If it says signature mismatch** when installing: the old app on the phone
used a different key → uninstall the old one first (`adb uninstall com.ventoydroid.app`).

---

## 7. The signing key — MOST IMPORTANT ⚠️

Files: `keystore/ventoydroid-release.keystore` + `keystore.properties`

- These prove to Google that updates come from you.
- **If you lose them, you can NEVER update the app on Play Store again.**
- **Never put them on the internet or in a public repo.** (They are already
  git-ignored — safe.)
- **Do this today:** copy both to Google Drive AND a pendrive.

If you ever move to a new PC: copy the whole project folder + these two
files, and builds keep working.

---

## 8. Play Console upload checklist

1. Create account: play.google.com/console → $25 one-time fee
2. Create app → name: VentoyDroid (or your choice)
3. Fill: store listing (paste texts from section 4) + privacy policy URL (section 5)
4. Upload `VentoyDroid-vX.Y.Z-release.aab` (the `.aab`, not the `.apk`)
5. Content rating questionnaire → ads: YES (it has ads)
6. Data safety form: "collects no data" + "ads shown via third party" —
   answer using the privacy policy as your guide
7. Submit for review (first review takes a few days)

---

## 9. Safety net (already set up)

- Every work session is committed to git — to go back to a known-good state:
  ```bash
  git log --oneline        # see history
  git checkout v0.2.4-ads-working   # the original working version
  ```
- Full backup zip: `ventoydroid-v0.2.4-backup.tar.gz` (one folder above this project)

---

*Last updated: v0.3.0 (versionCode 13), 2026-09-26*
