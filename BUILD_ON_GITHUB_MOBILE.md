# Build the APK from a phone

1. Open the repository's **Actions** tab.
2. Choose **Avakin Radar Android**, then select **Run workflow** (or wait for a push build to finish).
3. Open the successful run and download **AvakinRadar-debug-APK** under **Artifacts**.
4. Extract the ZIP on the phone, tap `app-debug.apk`, and follow Android's install prompt. If Android asks, allow your file manager or browser to install this APK.

The workflow runs unit tests before building. The APK is a debug build for testing, not a Play Store release. The source project is in `AvakinRadar_cloudkit/AvakinRadar`; the original project bundle is kept in the repository root.
