# Artist and album navigation verification

Verified on 2026-10-16 with the connected iQOO 16.

- Player artist and album names occupy separate lines and open their respective detail pages directly, without an intermediate action sheet.
- Native touch highlighting and long-press text selection are disabled on both metadata links. Keyboard focus remains visible.
- Real-device checks covered the existing QQ catalog track by ZUTOMAYO, artist profile/hot songs/albums, the current song album, the artist latest-release album, and returning from artist detail to the previous album.
- Playback remained active during the direct artist-navigation check, confirmed by Android MediaSession (PLAYING, error=null).
- JavaScript: 26 tests passed, including direct artist/album navigation and ignoring stale lookups after changing tracks or reopening the player. Java: 91 checks passed.
- Release APK built and v2/v3 signature verification passed. The prior APK was backed up; installation used an in-place update preserving app data. The installed APK was pulled back and matched the build SHA-256 below; packaged app.js, app.css, and index.html matched the working source.

APK SHA-256: `3e000d6ce6829acd06684170599ff2803397dafaafd6b1033cc807ac8975656a`

Device screenshots and backups remain local. This is a source feature update; the existing published release APK was not replaced.
