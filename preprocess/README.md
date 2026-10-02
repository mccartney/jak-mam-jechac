# preprocess

Turns the daily Warsaw GTFS (`https://436.pl/gtfs/warsaw.zip`, a fork of mkuran's WarsawGTFS) into one compact JSON per line for the app to download.
Run: `python3 build.py --feed /tmp/warsaw.zip --out out [--lines 504] [--gzip]`. Stdlib only. S3 upload + cron: TBD.

## App releases and versions

- **Version** comes from git: `versionName` = nearest `vX.Y.Z` tag (`git describe`), `versionCode` = commit count on `HEAD`.
- **Release:** `git tag v0.2.0 && git push origin v0.2.0`. `.github/workflows/release.yml` builds a signed APK, attaches it to a GitHub Release, and writes `app/version.json` to the bucket. The app reads that file at startup and shows a "Dostępna nowa wersja" banner when it's newer than itself.
- **Signing secrets** (one-time; keep the keystore safe — losing it means users must uninstall to update):
  `keytool -genkeypair -v -keystore release.jks -alias jmj -keyalg RSA -keysize 4096 -validity 10000`, then set repo secrets `JMJ_KEYSTORE_BASE64` (`base64 -w0 release.jks`), `JMJ_KEYSTORE_PASSWORD`, `JMJ_KEY_ALIAS`, `JMJ_KEY_PASSWORD`.
- **Who runs what:** every fetch sends `User-Agent: JakMamJechac/<name> (<code>; Android <n>)`, and the bucket's access logs land in `jak-mam-jechac-data-logs/access/` (kept 90 days):
  `aws s3 sync s3://jak-mam-jechac-data-logs/access/ logs/ && grep -oh 'JakMamJechac/[^ ]*' logs/* | sort | uniq -c`
