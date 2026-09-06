# Spendwise Android MVP

An offline-first Android expense tracker that supports manual entry and receipt screenshots shared from Google Pay (or any app).

## Included

- Manual entry and editing, with category, merchant, and a personal note
- Android image-share target (`Share` → `Spendwise`)
- On-device ML Kit OCR to suggest amount, payee, note, and category
- Mandatory editable review before saving
- Duplicate detection: a warning, never a block, so a genuine repeat payment is still recordable
- Attachments stored in app-private storage — the shared receipt is attached automatically, and
  more images can be added to any expense
- Editable categories, and people you can split an expense with
- Per-person balances with settle-up
- Room database transaction history and current-month category report

## Build

Open the folder in Android Studio (Ladybug or newer) and run on an Android 8.0+ device, or from a terminal:

```
./gradlew :app:assembleDebug              # debug APK
./gradlew :app:testDebugUnitTest          # parser and split-maths unit tests
./gradlew :app:connectedDebugAndroidTest  # database tests (needs a device/emulator)
./gradlew :app:assembleRelease            # minified release APK
```

Note that `connectedDebugAndroidTest` uninstalls the app afterwards, taking its database with it.

To try the share flow: make a Google Pay payment, capture its receipt screen, then use Android Share and select **Spendwise**.

## How the receipt parser works

Google Pay lays a receipt out as unlabelled lines — the amount, the payee and the message you typed
all arrive as bare text — so each field is found by structure rather than by keyword:

- **Amount**: currency-tagged figures win (the largest is the total on an itemised bill). With no
  currency symbol anywhere, the first number-only line wins, allowing a single leading symbol for a
  rupee glyph ML Kit failed to read. Long unbroken digit runs are rejected so a UPI reference is
  never mistaken for a price.
- **Note**: an explicit label if present, else the first line that is not receipt chrome. Action
  buttons such as "Pay again" are filtered by name, because ML Kit orders text blocks spatially and
  a button at the foot of the receipt can arrive ahead of your note.
- **Merchant**: anchored "paid to" patterns, including the label-on-its-own-line layout, with UPI
  handles reduced to a display name.

## Splitting

Each person's share is entered by hand; yours is whatever is left over, so the parts always
reconcile with the total. Balances count only unsettled shares, and settling one person never
touches anybody else's.

## Release signing

`assembleRelease` produces an unsigned APK unless a keystore is available. To sign, add these to
`~/.gradle/gradle.properties` (never to the committed `gradle.properties`):

```
RELEASE_STORE_FILE=/absolute/path/to/my-release-key.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=...
RELEASE_KEY_PASSWORD=...
```

## Schema changes

The database has no migrations: `fallbackToDestructiveMigration` recreates it whenever the version
changes. Write a real `Migration` before shipping a schema change to anyone whose history matters.
Default categories are seeded in `onOpen` whenever the table is found empty, which is the only hook
that covers both a fresh install and that rebuild.

## Privacy

Attachments are copied into app-private storage. OCR runs on-device, and the expense database is
excluded from Android's automatic cloud backup. This project does not access Google Pay data, SMS,
notifications, contacts, or bank accounts.

## Next recommended iteration

Recurring budgets, CSV export, a launcher icon, and settling by recording a repayment transaction
rather than only flagging shares as settled.
