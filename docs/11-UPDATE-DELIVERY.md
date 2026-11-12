# Update and Release Delivery Policy

## Goals
OSS should make updates easy without compromising package authenticity, user control, or an active LIVE session. The update system must support direct APK distribution during development and a store-managed channel if OSS is later published through an app store.

## Distribution modes
- **Store distribution:** the store is the source of truth for update delivery. Do not run a competing self-updater when store policy or platform behavior requires store-managed updates.
- **Direct APK distribution:** OSS checks the official release feed, downloads an update only with the configured user consent, validates metadata and package authenticity, then hands installation to Android's Package Installer. Android may require the user to confirm installation; OSS must not promise silent installation on ordinary non-rooted devices.
- **Development builds:** GitHub Actions artifacts are for testing only. They are not production update packages and must never be offered as stable releases.

## Update channels
- **Stable:** signed production releases, default channel.
- **Beta:** explicitly opted-in prerelease builds, clearly labeled as less stable.
- Channel changes require explicit user action. Never silently move a user from Stable to Beta.

## Release manifest
The official release feed must be versioned and served over HTTPS. Each release record should include:
- application ID and channel;
- semantic version and monotonically increasing versionCode;
- release date and concise release notes;
- minimum supported app version and Android API, where applicable;
- APK download URL, file size, and SHA-256 digest;
- expected signing-certificate SHA-256 fingerprint;
- rollout state (draft, staged, available, paused, withdrawn).

The manifest itself must be authenticated (for example, signed metadata verified with a public key embedded in OSS). HTTPS and a checksum alone are not sufficient proof of publisher authenticity. Android package-signature validation remains mandatory at installation.

## Client behavior
1. Check on app launch when online and periodically using a battery-conscious scheduler; do not poll continuously.
2. Respect network constraints and the user's download preference (Wi-Fi only / any network / ask).
3. Show version, channel, file size, release notes, and whether the update is recommended or mandatory.
4. Download to app-private storage, verify manifest authenticity, SHA-256, package ID, versionCode, and signing certificate before installation handoff.
5. Use Android's supported package installation flow. Request package-install permission only for direct-distribution builds and only when needed.
6. If a LIVE session or critical media operation is active, defer installation and show that the update is ready after the session.
7. Preserve user projects, assets, preferences, and diagnostics across updates. Run database migrations transactionally with backup/recovery.
8. If verification fails, discard the package, log a sanitized security event, and do not offer installation.

## User controls
Settings > Updates should provide:
- automatic update checks (on by default);
- automatic download preference (ask by default; Wi-Fi-only is the recommended default);
- Stable/Beta channel selection;
- update notification and release notes;
- check now;
- defer/remind later;
- current version, channel, and last successful check.

Users can postpone non-critical updates. A mandatory update may block starting a new LIVE session only when the installed version is genuinely incompatible or has a verified critical security issue; it must never interrupt an active LIVE session.

## Security and signing
- Production signing keys must be generated and stored outside the repository, never committed to Git or placed in public Actions artifacts.
- Use protected CI secrets or a dedicated signing service for release signing; restrict who can trigger production releases.
- Keep the signing key stable across upgrades. Losing or changing it would prevent normal in-place updates.
- Separate debug, beta, and production signing identities where appropriate; never allow a debug APK to replace a production installation.
- Publish provenance, license notices, and checksums alongside every release.

## Logging and privacy
Log check start/result, channel, available version, download progress summary, verification result, installation handoff/result where observable, and errors using stable update error codes and operation IDs. Never log signed URLs containing secrets, credentials, private signing material, or raw manifest authentication tokens. Update checks must not upload project data or diagnostics.

## Acceptance gates
Before enabling production auto-update:
- test upgrade from at least two previous versions and clean install;
- verify signature mismatch, wrong package ID, downgrade, corrupted APK, expired/withdrawn release, and invalid manifest are rejected;
- test offline, metered network, interrupted download/resume, low storage, and permission denied;
- verify projects, scenes, media references, settings, and local diagnostics survive upgrade;
- verify update installation is deferred while LIVE is active;
- test Stable/Beta isolation and staged rollout;
- verify a failed update cannot corrupt the currently installed application or user data.

## Current decision
Implement the update architecture and Settings UI contract early, but defer production download/install integration until the release channel, signing ownership, and distribution route are established. This avoids coupling OSS to temporary GitHub Actions artifacts or locking the project into a store decision prematurely.

## Current beta signing workflow

The repository provides a manually triggered GitHub Actions workflow at `.github/workflows/release-beta.yml`. It produces a signed release APK only when all four repository Actions secrets are configured:

- `OSS_RELEASE_KEYSTORE_BASE64`: base64-encoded Java keystore file;
- `OSS_RELEASE_STORE_PASSWORD`: keystore password;
- `OSS_RELEASE_KEY_ALIAS`: signing key alias;
- `OSS_RELEASE_KEY_PASSWORD`: signing key password.

The keystore must be generated and backed up securely by the project owner. Never commit the keystore or passwords, and do not regenerate the production key after beta distribution begins. Configure these values under GitHub repository Settings → Secrets and variables → Actions. Then run **Signed beta APK** manually from the Actions tab. The workflow validates that the secrets exist, builds the release variant, and uploads `oss-studio-beta-signed`. The normal Android CI continues to publish debug APKs for development only.

The release Gradle task intentionally fails when signing inputs are absent; an unsigned release must not be mistaken for a distributable beta. Before publishing, verify the artifact's package ID, versionCode, signing-certificate fingerprint, and installation/upgrade behavior on a physical Android device.
