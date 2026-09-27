# VentoyDroid — Publish & Earn Guide (for complete beginners)

Follow the parts in order. Part A first — everything else needs it.
Do it over several days, no rush. Nothing here costs money except
Play Store ($25 once).

---

## PART A — AdMob account (do this first, everything needs it)

**A1. Create the account**
1. Go to https://apps.admob.com in a browser (phone or PC)
2. Sign in with your Google account (Gmail)
3. Click **"Get started"** → it's free
4. Answer the questions: country = yours, timezone = yours,
   "Does you have a Google Payments profile" → follow the default

**A2. Register the app**
1. In AdMob: **Apps → Add app**
2. Choose **Android** → "The app is not listed on a supported app store yet" → Yes
3. Name it: `VentoyDroid`
4. After creating, click the app → **App settings**
5. You see **App ID** — looks like:
   `ca-app-pub-1234567890123456~1234567890`
   (16 digits, then **~**, then 10 digits)
   → Copy it. This goes in the app's AndroidManifest.xml (section A4)

**A3. Create the two ad units**
1. In AdMob: **Ads units → Add ad unit**
2. First: choose **Banner** → name it `VentoyDroid banner` → Create
3. Second: choose **Interstitial** → name it `VentoyDroid inter` → Create
4. You now have two **Ad unit IDs** — they look like:
   `ca-app-pub-1234567890123456/9876543210`
   (note: **/** slash, not ~)
   → Copy both

**A4. Give the IDs to your coding assistant (Buffy)**
Just paste a message like:
> "My AdMob App ID is ca-app-pub-XXXX~XXXX, banner is ca-app-pub-XXXX/XXXX,
> interstitial is ca-app-pub-XXXX/XXXX — put them in the app and rebuild"
The IDs go in exactly 2 files (`Ads.kt` + `AndroidManifest.xml` —
see HOW-TO-EDIT.md section 2) and a new release is built.

**A5. Payment info (do now, money comes later)**
1. AdMob → **Payments** → add your name, address, bank account
2. Tax info: fill the simple form (your country decides which)
3. Money is paid monthly once your balance passes **$100**
4. Also verify your address when Google mails you a PIN code
   (they post it, arrives in weeks — normal)

⚠️ **Never click your own ads, ask friends to click, or say "click my ads"
anywhere. Google detects it and bans the account forever.**

---

## PART B — Google Play Store (the big one, $25 once)

**B1. Create the account**
1. Go to https://play.google.com/console
2. Sign in with Google → pay $25 with a card (one time, forever)
3. Fill your developer name (can be your name or a brand like "BN Apps")

**B2. Create the app entry**
1. **Create app** → Name: `VentoyDroid` → App (not game) → Free
2. Accept the declarations

**B3. Fill the store listing** (copy-paste — texts already written!)
Everything is in your project folder `fastlane/metadata/android/en-US/`:
- Title → copy from `title.txt`
- Short description → `short_description.txt`
- Full description → `full_description.txt`
- Privacy policy URL → paste the link from Part E
- Graphic images: make a 1024×500 banner (any free tool like Canva,
  search "YouTube banner" size) and 2-8 phone screenshots (screenshot
  your app on the phone, power button + volume-down together)

**B4. Upload the app**
1. In Play Console: **Production → Create release**
2. Upload the file `VentoyDroid-v0.3.0-release.aab`
   (from your project folder — the **.aab**, not the .apk)
3. Release notes → copy from `fastlane/metadata/android/en-US/changelogs/13.txt`
4. Roll out!

**B5. Required questionnaires**
1. **Content rating**: questionnaire — answer "ads: yes", no violence etc.
   → get rating
2. **Data safety**: the app collects NO data itself; ads SDK does.
   Answers: "Does your app collect data? No" is allowed when the data
   is only for ads by third party — follow the privacy policy text
3. **App content**: ads = YES; add privacy policy link

**B6. Wait for review** — first review takes 1–7 days. They may email
questions; answer honestly. After approval the app is LIVE. 🎉

---

## PART C — GitHub (free, also shares your source)

**C1. Create account**
1. https://github.com → Sign up (free)

**C2. Publish the source (GPL requirement)**
Ask your assistant: *"push the VentoyDroid project to GitHub"* — it needs
a GitHub token from you (Settings → Developer settings → Personal access
tokens → generate, give it to the assistant once).
⚠️ NEVER upload `keystore/` or `keystore.properties` to GitHub.

**C3. Publish the APK release**
1. On your repo page → **Releases → Draft a new release**
2. Tag: `v0.3.0` → Title: `VentoyDroid v0.3.0`
3. Description: copy the changelog text
4. Attach the file `VentoyDroid-v0.3.0-release.apk` (APK here, not AAB)
5. Publish — users can now download the app free, ads still earn for you

---

## PART D — Amazon Appstore (free bonus store)

1. https://developer.amazon.com → Sign up (free, no fee)
2. **Add a new app** → Android → fill name/description (reuse Play texts)
3. Upload the **APK** (not .aab): `VentoyDroid-v0.3.0-release.apk`
4. Screenshots + content rating like Play
5. Submit — review takes a few days
6. Note: ads may not display on Fire tablets (no Google services) —
   the app works, you just don't earn from those users

---

## PART E — Privacy policy online (needed by B and D)

1. https://github.com → new public repository named `ventoydroid-privacy`
2. Upload the file `fastlane/metadata/android/en-US/PRIVACY_POLICY.md`
   renamed to `README.md` (or add it and make README too)
3. Repository → **Settings → Pages** → Source: main branch → Save
4. Your link appears in a minute:
   `https://YOUR-USERNAME.github.io/ventoydroid-privacy/`
5. Paste this link in Play Console (B3) and Amazon (D)

---

## ORDER TO DO EVERYTHING

1. **Today:** backup the keystore files (HOW-TO-EDIT.md section 7!) 
2. **Today:** Part A (AdMob) — takes 30 min
3. **Then:** give me the IDs → I update the app → new build
4. **Then:** Part E (privacy link) — 15 min
5. **Then:** Part B (Play Store $25) — the important one
6. **While waiting for review:** Part C (GitHub), Part D (Amazon)
7. **Later:** payment details in AdMob (A5) if you didn't already

## REALISTIC EXPECTATIONS 💰

- A USB-flashing utility gets opened rarely — expect small earnings
  (a few $/month unless the app gets popular)
- The GPL + GitHub combo can bring donations: consider a Ko-fi link later
- If the app gets popular on Play, THEN consider AdMob alternatives

*Guide for v0.3.0 (versionCode 13). Ask Buffy anytime a step is unclear.*
