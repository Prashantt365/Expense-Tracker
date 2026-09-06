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
- Three ways to split: custom rupee amounts, an even split, or percentages -- every share optional
- Per-person balances with settle-up
- An Insights dashboard: my own spending against what I front for others, a 12-month trend,
  category mix, per-person exposure after settlements, and weekday patterns -- settle straight
  from the per-person rows
- Import transactions from a bank or UPI statement PDF, reviewed row by row before anything saves
- Launcher shortcuts for Add, Split, Import and Balances, each pinnable to the home screen
- Add people from phone contacts, with near-duplicate names flagged rather than silently merged

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

Three modes, chosen per expense:

- **Custom** -- type each person's rupee share. Leaving a field blank leaves that person out.
- **Equal** -- divided evenly across everyone added, plus you. Indivisible paise land on you.
- **Percent** -- type each person's percentage; yours is the remaining percentage.

Whichever mode is used, your share is the remainder, so the parts always reconcile with the total
and no rounding scrap goes missing. Switching between Custom and Percent clears the typed figures,
since the units differ. Balances count only unsettled shares, and settling one person never
touches anybody else's.

## Insights

The dashboard separates two questions that a single total conflates: what you actually spent, and
what you are carrying for other people.

- **My own spending** strips out every share assigned to somebody else, so categories, the weekday
  pattern and the month-over-month comparison all describe your money alone.
- **Spent on other people** shows the effective cost after settlements: what you put out, what has
  come back, and what is still outstanding -- overall and per person.
- The 12-month trend stacks both parts, so a heavy month that was mostly fronted for others reads
  differently from one that was all yours.

Every figure is computed by pure functions in `Analytics.kt` over the loaded expense list, which is
why the arithmetic is unit-tested rather than buried in SQL.

## Importing a statement

PdfRenderer only rasterises pages, it does not expose their text, so each page is rendered and put
through the same on-device OCR the receipt flow uses. That keeps the work offline and adds no
dependency, at the cost of reading the page as an image.

The parser reads by shape rather than by column, since layouts vary by issuer: a date near the
start, one or more money figures, and whatever text lies between them. Where a row carries more
than one figure the trailing one is treated as the running balance and dropped. Credits are
detected and arrive unticked. Nothing is written until the review screen is confirmed, and rows
that duplicate an existing expense are skipped on import.

The layouts it is tuned for are the common Indian bank and UPI statement shapes. If your statement
reads badly, the fix belongs in `StatementParser` and its unit tests.

## Contacts

READ_CONTACTS is requested at runtime, only from the people settings, and only when you ask to
import. Names are read; nothing is written back and nothing leaves the device.

A phone book routinely holds the same person more than once, so every candidate is compared
against the existing people and against the rest of the import. Reversed name order, an
abbreviated surname, honorifics, accents and typos all count as a match. Flagged names arrive
unticked rather than dropped: a false flag costs one tick, whereas a missed duplicate corrupts
every balance that person appears in.

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
