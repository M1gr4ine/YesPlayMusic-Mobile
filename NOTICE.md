# Notices and attribution

## YesPlayMusic

This is an unofficial Android adaptation based on the ideas and user experience of [YesPlayMusic](https://github.com/qier222/YesPlayMusic), created by qier222 and contributors. It is not an official YesPlayMusic release and is not affiliated with the upstream project or its contributors.

The upstream project is distributed under the MIT License. Its copyright notice and license text are retained in this repository's [LICENSE](LICENSE). The mobile client is a separate Android implementation; it does not bundle Electron or the upstream desktop application.

## Protocol references and services

The NetEase request paths, payload conventions, and WeAPI/EAPI protocol parameters in this Android client were checked against the YesPlayMusic 0.4.10 desktop bundle's embedded `@neteaseapireborn/api` 4.29.7 package. The package metadata in that local bundle declares MIT. This Android client does not bundle that Node.js package or copy its implementation; it independently implements the request protocol in Java.

The client calls NetEase Music and QQ Music HTTPS endpoints. Their availability, authentication requirements, catalog data, and playback rights are controlled by those services and may change.

## Media and trademarks

Song recordings, album artwork, lyrics, service names, and trademarks remain the property of their respective owners. The screenshots in `docs/screenshots/` show real app screens; media displayed in them is not covered by the source code license. No recordings, account credentials, signing keys, device identifiers, or private runtime logs are distributed with the source.
