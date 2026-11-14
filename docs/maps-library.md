# Saved-place imports and static list snapshots

The **Offline saved places** patch keeps the existing Local saved screen, native
Save sheet, labels, list emoji support and account-pull behavior. These additions
do not require a Google account, a Places API key or a separate server.

## Import a library

Open **Local saved > Import** and select a backup, Google Takeout saved-list CSV,
Saved Places GeoJSON, KML, a list snapshot, several files, or a Takeout ZIP.
The ZIP reader selects `Saved/*.csv` and `Saved Places.json`; unrelated Takeout
data is not imported.
The result reports how many files were read, including when all their entries
were already present.

CSV titles, multiline notes, comments, tags, headers and original rows are kept.
GeoJSON features retain their original properties and geometry. KML imports keep
names, folder memberships, descriptions and original coordinate strings.
Rows without a resolvable identifier or position remain visible in the list.
A viewport center is not a place position; an explicit valid `(0, 0)` is.
Duplicate entries in the same list keep their own notes and identities. Reimporting
the same CSV rows does not multiply them. Overlapping lists keep their respective
titles, source links and notes.

Full-library backups remain private backups, not sharing files. Versions 1 and 2
are migrated with an untouched versioned copy of the original store. Version 3
backups are also readable; opaque extra metadata is preserved without activating
unsupported features. Unsupported versions, malformed documents and conflicting
unknown fields produce an error rather than a partial rewrite. An unreadable local
store is not overwritten. A failed document is rolled back; successfully read
files in a multi-file import remain, with file-specific warnings.

An update also repairs the earlier enrichment queue once: entries whose retries
were exhausted by a lost network can resume without importing the files again.
The original store is retained as `ungoogled_saved.json.queue-v0.bak`. Your pause
choice, cancelled entries and identity-review decisions are preserved. This
migration does not reset genuine failures on every launch.

## Available native details, without a key

On the supported signed-out Maps build, **26.36.04.973607363**, imports can use
Maps' own place-details service. This does not scrape web pages or automate taps.
Requests still go to Google. Pause enrichment before importing if you want to
keep the import offline; the original entries do not depend on network success.
The microG account path remains unchanged and does not gain background enrichment.

Feature IDs, decimal CIDs and opaque Place IDs are kept distinct. Only a complete,
recognized typed Place-ID envelope is decoded. Short Maps links may follow
bounded HTTPS redirects on supported Google hosts. No arbitrary imported photo
URL is requested, even if it points to a Google image host. Supplied photo fields
remain inert original data in the backup; only locally captured or newly fetched
native photo URLs are eligible for thumbnail requests.

When available, the local details cache includes the category, rating, review
count, opening hours and a representative thumbnail. A downloaded thumbnail means
decoded image bytes were saved, not merely a URL. Native photo candidates are
tried only to obtain a representative image, not to copy an entire gallery or
reviews.

| State | Meaning |
|---|---|
| Queued / loading | Waiting for a usable network, Maps' service, or a request in progress. |
| Retry scheduled / failed | A bounded retry is pending, or manual Retry is needed. |
| Unavailable | No supported identifier or native listing was returned; the entry stays. |
| Identity review | Maps returned a different listing. Its name alone does not establish an alias. |
| Complete | Available details are local; thumbnail status is reported separately. |
| Cancelled | The queue was cancelled, not the saved entry. |

Use **Pause**, **Resume**, **Retry failed** or **Cancel queue** in Local saved.
Review a proposed replacement from the entry's menu; no replacement is accepted
automatically. Original source links and import rows are retained after confirmation.

Import, Resume and reopening Maps with unfinished work can start a **visible
Android `dataSync` foreground service**, only while the app is in the foreground.
Its notification shows progress and offers Pause and Cancel queue. Native request
handles are retained for the job and released when it stops. A bounded partial
wake lock is held only for active work, not while waiting offline. This is an
on-device service, not a companion server or an always-running sync feature.

Disconnected or captive-portal networks do not consume place or thumbnail
attempts. A connection change or service stop during a failed request does not
spend its retry budget. Reconnection wakes existing authorized work; it does not
start a new foreground service from the background. Internet-capable networks are
allowed even when Android's validation probes are blocked. Genuine native
failures on a connected network still have bounded retries, and a returned
identity mismatch still requires review.

Controls and checkpoints run off the UI thread, including when import parsing
holds the store lock. A normal service stop waits for its pause/cancel checkpoint.
Android's `dataSync` timeout instead stops the service immediately and requests a
paused checkpoint; reopen Local saved and tap Resume to continue. Android 15+
limits background `dataSync` work to six hours per 24 hours across the app's
services. Force-stop, device restart, power management and platform restrictions
can also stop work; reopen Maps to resume unfinished work unless you paused or
cancelled it.

If notifications are denied, Android can still show the foreground job in
**Active apps** rather than the notification drawer. No notification permission
prompt is forced. A start restriction or missing installed service is reported
without deleting the queue or using retries. Root/mount installs that retain the
stock manifest may require a normal patched install for background enrichment;
imported rows and ordinary saved-place actions remain available.

Library writes run off the UI thread and use atomic checkpoints. Enrichment is
checkpointed in small batches, map refreshes are coalesced, and long lists recycle
their visible rows. Thumbnail work is bounded; queued work for detached rows is cancelled.
Images are limited to 2 MiB per download and 16 million input pixels; the disk cache
is capped at 256 MiB. Cache eviction, lack of space, missing listings or network
failures can leave some thumbnails unavailable. Import text and individual saved
files inside a ZIP are limited to 64 MiB. A library checkpoint exceeding the same
limit is rejected rather than writing a file that cannot be reopened.

## Share one static list

Open a list and choose **Share > Export snapshot file** or **Share snapshot file**.
The disclosure explains what leaves the device before the file picker opens.
The recipient uses **Local saved > Import**, which creates an independent local
copy. Later edits do not sync. Anyone with the file can read and re-share it.
Plain-text link sharing remains available.

The snapshot contains only the selected list's title, emoji, description, entry
titles, source links, source-provided coordinates and explicit **list notes**.
Private global notes are separate and are never substituted for missing list
notes. Home, Work, labels, history, other list memberships, credentials and native
photo/rating/review/hour caches are not projected. Source links containing
credentials are rejected rather than shared.

Native-fetched coordinates are not exported as independent map data. A reference
without source-provided coordinates remains usable in the list and may resolve
inside the recipient's native Maps app. The existing emoji feature is reused,
including complete multi-code-point emoji roundtrips; default list icons and
branding are unchanged.

For compatibility, the JSON identifier remains `trailmark-list`, version `1`.
It is a file format identifier, not an app rename or a dependency on a server.
The reader validates the complete snapshot before adding it. Unknown fields,
unsupported versions, invalid coordinates and duplicate entry IDs are rejected.
Version 1 limits list titles to 200 characters, entry titles to 300, descriptions
and list notes to 2,000, source links to 2,048 and a complete emoji to 64 UTF-16
code units. Oversized values produce an error, not a truncated export.
No live collaboration, invitation tokens, roles, encryption setup, companion
server, external geometry service or web UI is included.

## Focused regression tests

With the project's normal JDK 21, Android SDK and Gradle repository access:

```sh
./gradlew :extensions:extension:testDebugUnitTest :patches:buildAndroid
```

Tests use generated local records, not harvested fixtures or APK contents.
They cover import preservation, source identity, recovery, selected-list privacy,
emoji and duplicate-entry roundtrips, and offline thumbnail completion. Production
extension configurations continue to exclude Kotlin stdlib; test-only dependencies
are not included in the patch bundle.
