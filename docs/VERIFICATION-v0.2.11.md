# v0.2.11 verification

Date: 2026-10-16 (Asia/Shanghai).

- Feature source: db4e2ef. The release increments Android versionName to 0.2.11 and versionCode to 13.
- All packaged assets and DEX are byte-for-byte identical to the device-tested feature APK documented in [artist/album navigation verification](VERIFICATION-artist-album-navigation.md).
- The feature implementation passed 26 JavaScript tests and 91 Java checks. No feature source changed during release preparation.
- The APK passed v2/v3 signature verification and aapt2 package/version checks; it is not debuggable.
- Installed in place on iQOO 16, preserving existing app data. Device package metadata reports versionName 0.2.11 and versionCode 13.
- Installed base.apk was pulled back and its SHA-256 matched the local release APK.
- This publication does not claim new device validation of every feature added since v0.2.10; detailed navigation checks are recorded in the linked feature verification.

SHA-256: `f3b3e37db452527c06d59b66ba3835306fbb33ac288a3b22641a892961a630b1`
