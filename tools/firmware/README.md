# Getting an APK out of official firmware

Some apps in this bundle are never published as an APK, not even by the vendor: they only exist inside the
phone's firmware (RedMagic Game Space, for one). This bundle never hosts vendor APKs. You download the vendor's
own firmware and take the app out of it yourself. `payload.py` does that without downloading the whole firmware:
it reads only the partition you ask for, straight from the vendor's server.

What you need: Linux, macOS or WSL with `python3` and `curl`, plus `debugfs` (package `e2fsprogs`) for ext4
images or `fsck.erofs` (package `erofs-utils`) for EROFS images. The `zstandard` Python module is needed only if
the script says so.

## 1. Find the official firmware

Use the vendor's own download page. It must be a **full** OTA (`update.zip` with a `payload.bin` inside, usually
several GB), not an incremental update. [APK-SOURCES.md](../../docs/APK-SOURCES.md) gives the link for each app
that needs this.

## 2. List the partitions

```sh
python3 payload.py "<firmware URL or local update.zip>" list
```

This reads a few hundred KB and prints each partition with the amount it would download. Apps live in `system`,
`system_ext` or `product` (OPPO/OnePlus also use `my_*` partitions).

## 3. Extract one partition

```sh
python3 payload.py "<firmware URL>" extract system system.img
```

Only that partition's data is downloaded (for RedMagic 9 Pro `system`: about 3.6 GB of a 6.3 GB zip). `JOBS=3`
lowers the number of parallel requests if the server complains. A local `update.zip` or bare `payload.bin` works
the same way.

## 4. Copy the APK out

Check the file system first with `file system.img`.

**ext4** (`debugfs` reads the image, no root or mounting needed):

```sh
debugfs -R "ls -l /system/priv-app" system.img                                       # find the app's folder
debugfs -R "dump /system/priv-app/GameSpace/GameSpace.apk GameSpace.apk" system.img   # copy the APK out
```

Android 10 and newer keep everything under `/system` inside the system image. In `system_ext` and `product`
images, and in older system images, the folders sit at the top, so drop the `/system` prefix there.

**EROFS** (most phones from Android 13 on):

```sh
fsck.erofs --extract=system_out system.img
ls system_out/system/priv-app
```

## 5. Check the APK

Check that it is the vendor's original, signed by the vendor, before you patch it:

```sh
aapt dump badging GameSpace.apk | head -1          # package name and version
apksigner verify --print-certs GameSpace.apk       # signer
```

Then open Morphe Manager, choose the APK from storage and patch it as usual.
