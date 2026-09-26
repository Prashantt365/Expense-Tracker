# Peyo Android MVP

An offline-first Android expense tracker that supports manual entry and receipt screenshots shared from Google Pay (or any app).

## Included

- Manual entry and editing, with category, merchant, and a personal note
- Android image-share target (`Share` → `Peyo`)
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
- Two home screen widgets: a resizable spending summary with quick actions, and a one-tap
  Add expense button
- A theme of its own in light and dark, with the phone's wallpaper colours available on request
- Add people from phone contacts, with near-duplicate names flagged rather than silently merged
- An optional account, so transactions survive a reinstall and reach a second phone — backed up
  automatically, restorable on demand, and skippable entirely
- A local backup file that needs no account and no connection, and can be imported back

## Accounts, backup and restore

The app opens on three choices — create an account, sign in, or continue without one — and then
asks once which currency to show amounts in. All three routes lead to the same app: every write
lands in the local database first, so an account is a backup rather than a licence to use
Peyo, and losing your connection costs you the backup and never the data.

**Automatic backup is on by default.** Anything recorded or edited is sent a few seconds later,
and once more when the app opens. The switch is in Settings for anybody who would rather send
their data on their own schedule; the manual *Back up now* button is always there either way.

**Restore** reads the whole of an account's history back down, ignoring how far the incremental
sync thinks it has got. That is the button to use on a freshly reinstalled app or a new phone.
Nothing local is discarded by it: rows here that the server has not seen are kept and sent up.

**Back up locally** writes every transaction, person and category to a file you choose, and
**Import file** merges one back in. Rows are matched by the id they carry rather than by their
position, so a file taken on one phone imports cleanly onto another, and importing the same file
twice changes nothing. Deletions travel as deletions, so an import cannot resurrect what you
removed before taking the backup. Receipts and attachments are in neither backup: they are the
bulky part, and they stay on the device by design.

To enable accounts, create a Supabase project, run `supabase/schema.sql` in its SQL editor, and put
the project URL and publishable (anon) key in `local.properties`:

```
supabase.url=https://<project>.supabase.co
supabase.anonKey=<anon key>
```

A build without those stays wholly offline and shows the account options as unavailable; the
local backup file still works. The anon key is meant to ship inside the APK — row level security on every table, not
the key, is what keeps one account out of another's data.

`schema.sql` is re-runnable: applying it twice changes nothing, so run it again after pulling
changes. Alongside the four data tables it creates `public.profiles`, one row per registered user
carrying the address they registered with and an `auth_completed` flag that is true once Supabase
Auth has fully accepted them. Both are written by a trigger on `auth.users` rather than by the app,
and the same goes for the `user_email` column on each data table: an email a client could set is an
email a client could set to somebody else's, and a "verified" flag a client could raise would mean
nothing at all.

## Build

Open the folder in Android Studio (Ladybug or newer) and run on an Android 8.0+ device, or from a terminal:

```
./gradlew :app:assembleDebug              # debug APK
./gradlew :app:testDebugUnitTest          # parser and split-maths unit tests
./gradlew :app:connectedDebugAndroidTest  # database tests (needs a device/emulator)
./gradlew :app:assembleRelease            # minified release APK
```

Note that `connectedDebugAndroidTest` uninstalls the app afterwards, taking its database with it.

To try the share flow: make a Google Pay payment, capture its receipt screen, then use Android Share and select **Peyo**.

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

Two things make that workable on a real statement.

**The page is rebuilt into visual rows.** ML Kit groups text into blocks by proximity, so a
statement laid out in columns -- as the Google Pay transaction statement is, with Date & time,
Transaction details and Amount side by side -- can come back as one block per column: every date,
then every payee, then every amount. Reading the recognised text in the order it arrives would
destroy the association between them. `PdfTextReader` instead groups recognised lines by vertical
position and orders each group left to right, restoring the row a reader actually sees.

**Transactions are read as records, not lines.** A bank statement puts a whole transaction on one
line, but a Google Pay entry spreads across three: date above time, payee above the reference,
amount off in its own column. A line beginning with a date opens a record, following lines join it,
and the fields are pulled from the record as a whole.

From there:

- A currency marker makes even a bare integer safe to read as money, while an untagged figure needs
  a grouping comma or two decimals. That keeps UPI reference numbers and account tails out of the
  amount column.
- Where a record carries several figures the trailing one is treated as a running balance and
  dropped, so a bank row imports the payment rather than the balance.
- The statement period and the Sent/Received summary tiles are skipped. The period row starts with
  a date and carries two large figures, so without that it would import as a transaction.
- Credits are detected and arrive unticked, and "Paid to" wins outright, so a payment to a shop
  with "Credit" in its name is still a payment.
- Times of day are kept, so imported rows sort correctly within a date.

Nothing is written until the review screen is confirmed, and rows duplicating an existing expense
are skipped on import.

Tuned for the Google Pay transaction statement and common Indian bank and UPI layouts. If yours
reads badly, the fix belongs in `StatementParser` and its unit tests.

## Home screen widgets

Two, both written with Glance -- Compose against RemoteViews -- so they draw from the same Room
database and the same colour scheme the app does, rather than from a parallel copy of either.

**Spending summary** is one widget at four sizes rather than four widgets. Which figures somebody
wants is not really the question; how much of their home screen they will give up is. A 2x1 strip
shows this month's own spending alone, a 2x2 adds what is still owed and who owes most of it, a 4x2
adds Add, Split, Balances and Import as buttons, and a 4x4 adds the last four transactions. The
launcher picks by the size actually on screen, so resizing it is how you choose.

**Add expense** is a single button, for anybody who wants recording a payment to be one tap from
the home screen and does not want a panel of figures sitting there the rest of the time.

Both are placeable from Settings on a launcher that allows it, and by long-pressing the home screen
on one that does not. Every button opens `MainActivity` with one of the four actions the launcher
shortcuts already use, so a widget button and a long-press shortcut cannot drift apart.

They redraw whenever the expense list changes, keyed on the list rather than on each place that
writes to it -- so a statement import, a restore and a settle-up all reach the home screen the same
way a typed expense does. The half-hourly `updatePeriodMillis` in the widget XML is only a backstop
for a widget that has been sitting untouched; without the explicit refresh the total on the home
screen could disagree with the total in the app for thirty minutes, which is the most visible way
for a widget to look broken.

The widgets follow the app's own theme setting, not the phone's. Somebody who has chosen Dark
inside Peyo while their phone is on Light would otherwise get a light widget beside a dark app.

## Look and feel

The app has a palette of its own, built out from the sprout in the launcher icon rather than left
on the Material baseline purple, with hand-written light and dark schemes in
`ui/theme/Color.kt`. Dynamic colour is offered in Settings but is off by default: a tracker whose
hero card means something by its colour -- green for what has come back, warm for what is still out
-- cannot keep that promise on a scheme derived from somebody's wallpaper.

Three things are worth knowing if you are changing the UI:

- **A category's colour is a hash of its name**, not its rank in the current period, so Food is the
  same green in a month it leads the list and a month it does not. `categoryColor` in
  `ui/Components.kt` is the one place that decides it, and the donut, the bars, the badges and the
  transaction rows all read from it.
- **Charts take their palette from the theme** through `LocalChartPalette`. A single set of
  mid-tones is the usual shortcut and it fails at both ends: dark enough to read on a pale surface
  is too dark to read on a black one.
- **Full-screen screens are overlays, not dialogs.** The editor, the statement review and the
  contact picker are drawn in the activity's own window by `FullScreenOverlay`, with `BackHandler`
  for the back gesture. In a dialog window each one measured the system bars twice and squeezed its
  pinned action to a few pixels against the bottom of the screen.

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

Versions 2 onwards migrate properly — see `MIGRATION_2_3` and `MIGRATION_3_4` in `AppDatabase`.
Only version 1 predates anything worth keeping and is still dropped and rebuilt. Write a real
`Migration` for anything new; a destructive fallback now costs somebody their history.

Default categories are seeded in `onOpen` whenever the table is found empty, which is the only hook
that covers both a fresh install and a rebuild. Those seeded rows are also why the first sync after
a reinstall has to reconcile identities by name: the account already holds a category called Food,
the fresh install has just made its own, and inserting the pulled one beside it would break the
unique index on the name.

## Privacy

Attachments are copied into app-private storage. OCR runs on-device. The expense database, the
attachments, the signed-in session and the sync position are all excluded from Android's automatic
backup and device transfer. Contacts are read only when you ask to import names, and only names;
this project does not access Google Pay data, SMS, notifications, or bank accounts.

## Next recommended iteration

Recurring budgets, CSV export, and settling by recording a repayment transaction rather than only
flagging shares as settled.
