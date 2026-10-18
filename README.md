# NPTEL Library (Android)

Browse NPTEL by subject, then course, then lectures. Lectures load live from each course's
official YouTube playlist as you scroll, play inside the app with auto-next, and any lecture
can be bookmarked. Nothing is downloaded: only the app (~1 MB) and your bookmark list
(a few KB) are stored on the phone.

Build: push this folder to a GitHub repo. Actions builds the APK
(Actions tab -> latest run -> Artifacts -> NPTEL-Library-apk).

To add or fix courses, edit the `COURSES` list inside `app/src/main/assets/index.html`
(each entry is {d: subject, t: title, p: YouTube playlist ID}).
