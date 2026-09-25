# InstaLimit

Android app that monitors Instagram usage locally and sends a reminder when the configured daily limit is reached.

## GitHub Actions APK build

1. Create a GitHub repository named `InstaLimit`.
2. Upload the contents of this repository to the `main` branch.
3. Open **Actions**.
4. Select **Build Android APK**.
5. Run the workflow with **Run workflow**, or push to `main`.
6. After the build finishes, open the workflow run.
7. Download the **InstaLimit-App** artifact.

The project is configured for JDK 17, Gradle 8.11.1, Android Gradle Plugin 8.7.3, Kotlin 2.0.21, and compile/target SDK 35.

The manifest uses Android framework resources for the launcher icon and activity theme, so the project does not depend on missing generated launcher/theme resources.

No Android Studio is required to build the debug APK through GitHub Actions.
