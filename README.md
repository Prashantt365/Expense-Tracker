# Spendwise Android MVP

An offline-first Android expense tracker that supports manual entry and receipt screenshots shared from Google Pay (or any app).

## Included

- Manual entry with category, merchant, and a personal note
- Android image-share target (`Share` → `Spendwise`)
- On-device ML Kit OCR to suggest amount, payee, note, and category
- Mandatory editable review before saving
- Room database transaction history and current-month category report

## Build

Open the folder in Android Studio (Ladybug or newer) and run on an Android 8.0+ device, or from a terminal:

```
./gradlew :app:assembleDebug      # debug APK
./gradlew :app:testDebugUnitTest  # receipt-parser unit tests
./gradlew :app:assembleRelease    # minified release APK
```

To try the share flow: make a Google Pay payment, capture its receipt screen, then use Android Share and select **Spendwise**.

## Release signing

`assembleRelease` produces an unsigned APK unless a keystore is available. To sign, add these to
`~/.gradle/gradle.properties` (never to the committed `gradle.properties`):

```
RELEASE_STORE_FILE=/absolute/path/to/my-release-key.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=...
RELEASE_KEY_PASSWORD=...
```

## Privacy

The screenshot URI is saved only as a reference. OCR runs on-device, and the expense database is
excluded from Android's automatic cloud backup. This project does not access Google Pay data, SMS,
notifications, contacts, or bank accounts.

## Next recommended iteration

Add recurring budgets, copy receipt images into app-private storage (the shared URI grant lapses,
so the stored reference is not readable later), CSV export, editing of saved expenses, and a launcher icon.
