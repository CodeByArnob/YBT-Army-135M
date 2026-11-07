# Android production signing

The APK distributed from a GitHub Release must be a release build signed with one persistent developer-owned upload key. Debug APKs are for testing only and are not production releases.

## Create the key once

Run this on a trusted computer. Keep the keystore and passwords in a secure password manager and encrypted backup. Never commit the keystore to Git.

```bash
keytool -genkeypair -v -keystore ybt-army-release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias ybt-army
```

## Add GitHub Actions repository secrets

In GitHub, open Settings → Secrets and variables → Actions → New repository secret.

Create these four secrets:

- `YBT_ANDROID_KEYSTORE_BASE64`: Base64-encoded contents of `ybt-army-release.jks`
- `YBT_ANDROID_KEYSTORE_PASSWORD`: the keystore password
- `YBT_ANDROID_KEY_ALIAS`: `ybt-army` (or the alias chosen above)
- `YBT_ANDROID_KEY_PASSWORD`: the key password

Linux/macOS:
```bash
base64 -w 0 ybt-army-release.jks
```

PowerShell:
```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("ybt-army-release.jks"))
```

Paste the resulting Base64 string into the first secret. Do not paste any password into an issue, pull request, commit, chat, or workflow log.

## Release policy

- Pull requests build a debug APK for testing only.
- Android version tags build a signed release APK.
- A tag build intentionally fails if signing secrets are missing.
- Never generate a new signing key for each release. Losing the key can prevent updates from being accepted as updates to the installed app.
- Keep Play Protect enabled. Signing is necessary for release identity, but it does not guarantee that Play Protect will immediately stop showing a first-time developer warning.
