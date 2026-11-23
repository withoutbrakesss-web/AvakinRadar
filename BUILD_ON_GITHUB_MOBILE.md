# Build and test Avakin Radar from a phone

## Download the APK
1. Open the repository's **Actions** tab.
2. Choose **Avakin Radar Android**.
3. Open the latest successful run.
4. Under **Artifacts**, download **AvakinRadar-debug-APK**.
5. Extract the ZIP and tap `app-debug.apk`. If Android asks, allow your file manager/browser to install apps from this source.

The workflow runs unit tests and builds the debug APK. It is a test build, not a Play Store release.

## Optional live-store test on an emulator
1. Open **Actions**.
2. Choose **Live public catalog emulator test**.
3. Press **Run workflow** and confirm.
4. Wait for the run to finish; if it fails, open the run and share a screenshot of the failed step/log.

This manual test launches Android API 35 emulator and checks the live public catalog, snapshot persistence, UI results, and a second scan/comparison. It has not yet been run. The source project is in `AvakinRadar_cloudkit/AvakinRadar`.
