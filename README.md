# Spendwise Android MVP

An offline-first Android expense tracker that supports manual entry and receipt screenshots shared from Google Pay (or any app).

## Included

- Manual entry with category, merchant, and a personal note
- Android image-share target (`Share` → `Spendwise`)
- On-device ML Kit OCR to suggest amount, payee, note, and category
- Mandatory editable review before saving
- Room database transaction history and current-month category report

## Build

1. Open this folder in Android Studio (Ladybug or newer).
2. Let Gradle sync, then run on an Android 8.0+ device or emulator.
3. Make a Google Pay payment, capture its receipt screen, then use Android Share and select **Spendwise**.

The screenshot URI is saved only as a reference. OCR runs on-device. This project does not access Google Pay data, SMS, notifications, contacts, or bank accounts.

## Next recommended iteration

Add recurring budgets, receipt image encryption/copying to app-private storage, CSV export, and automated tests for the OCR parser.
