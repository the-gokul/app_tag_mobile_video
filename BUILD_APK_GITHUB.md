# Build APK with GitHub Actions

1. Push to `main` (or run **Actions → Build Tag APK → Run workflow**).
2. Open the green run → **Artifacts** → download **tag-mobile-video-debug-apk**.
3. Unzip → install `app-debug.apk` on the phone.

Repo: https://github.com/the-gokul/app_tag_mobile_video

A GitHub Release is also created on each successful `main` build (`v0.3.0-buildN`) with the same APK attached.
