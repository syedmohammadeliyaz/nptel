# Course Hub — Android app source

A complete Android project that wraps the Course Hub app (513 NPTEL courses, search, categories, bookmarking). The whole app is bundled **inside** the APK, so there's no hosting, no PWABuilder, and no manifest to get wrong.

Two ways to get your `.apk`. Pick one.

---

## Option A — Build on GitHub (no installs, works from a phone)

GitHub builds the APK for you on their machines and hands you the file.

1. Create a new repository on github.com (public or private, either works).
2. Upload **everything in this folder**, keeping the folder structure exactly as it is. Easiest way: zip nothing, just use **Add file → Upload files** and drag the whole `android` folder contents in. The `.github` folder must come along — if your file picker hides it, upload via the GitHub website's drag-and-drop area, which keeps hidden folders.
3. The build starts by itself on upload. Open the **Actions** tab to watch it — takes about 3–5 minutes the first time.
4. When it finishes (green check), click into the run and scroll to **Artifacts** at the bottom. Download **CourseHub-apk**.
5. That downloads a zip — open it and take `app-debug.apk` out.
6. Move the APK to your Android phone, tap it, allow "install unknown apps" when asked. Done.

If the run fails, open the failed step in Actions and read the last few red lines — that tells you exactly what's missing.

---

## Option B — Build offline on your own computer

Needs about 1.5 GB of downloads the first time, then works with no internet.

1. Install **Android Studio** (free, from developer.android.com/studio). It brings the Android SDK and Java with it.
2. Open Android Studio → **Open** → select this `android` folder.
3. Wait for it to finish syncing Gradle (status bar at the bottom). It downloads what it needs on first run.
4. Menu: **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
5. When the popup appears, click **locate** — your APK is at `app/build/outputs/apk/debug/app-debug.apk`.

Prefer the command line? From this folder, with the Android SDK installed and `ANDROID_HOME` set:

```
gradle assembleDebug
```

---

## Notes

- **Debug vs release:** this produces a *debug* APK, which is signed with Android's standard debug key. It installs and runs on any phone — that's all you need for personal use. It just can't be uploaded to the Play Store as-is; that would need a release build with your own signing key.
- **Offline behaviour:** browsing, searching, filtering, and bookmarking all work with no internet, since the course list ships inside the app. Playing a lecture streams from YouTube, so that part needs a connection.
- **Changing the app:** everything you see lives in `app/src/main/assets/index.html`. Edit it, rebuild, done. To add courses, find the `const COURSES = [` line in that file and add entries with the same `{discipline, title, playlistId}` shape.
- **Renaming the app:** change the value in `app/src/main/res/values/strings.xml`.
