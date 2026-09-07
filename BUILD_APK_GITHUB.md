# Build APK with GitHub Actions

Repo: https://github.com/the-gokul/app_tag_mobile_video

## Steps

1. Push to `main` (or **Actions → Build Tag APK → Run workflow**).
2. Wait for the green check on workflow **Build Tag APK**.
3. Open the run → **Artifacts** → download **tag-mobile-video-debug-apk**.
4. Unzip → install `app-debug.apk` on the phone.

A GitHub Release `v0.3.0-buildN` is also created on successful `main` builds with the same APK.

## If the workflow fails

1. Open the red run → download artifact **build-log**.
2. Search for `FAILURE:` / `error:` / `resource … not found`.
3. Fix the reported file, push again.

### Known failure (run #9 / commit `eb73813`)

`activity_device.xml` referenced a non-existent framework drawable
`@android:drawable/ic_menu_always_invoke_with_default_browser` for the Flash button.
That made `:app:processDebugResources` fail. Fixed by using `@drawable/ic_flash` and
`@drawable/ic_switch_camera`.

Full step-by-step CI flow: see **GitHub Actions** in `README.md`.
