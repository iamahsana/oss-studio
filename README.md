# Open Stream Software (OSS)

Open Stream Software (OSS) is a lightweight, modular Android live-streaming studio. It combines scene editing, media sources, audio mixing, and multi-platform RTMP streaming in one performance-focused application, with an extensible community module ecosystem.

> **Status:** Product and engineering foundation documentation. Android scaffold is present; CI validation and third-party component audit are in progress.

## Product direction
- Android first; iOS is postponed.
- Performance-first, LIVE-first architecture.
- Scene composition is the actual LIVE output. A separate live preview renderer is not part of the initial release.
- Modular Core + Extension Center.
- Multi-destination RTMP/RTMPS.
- Dedicated scene editor and audio mixer.
- Phone-first build and test workflow through GitHub Actions.

## Documentation
1. [Product Specification](docs/01-PRODUCT-SPECIFICATION.md)
2. [Technical Architecture](docs/02-TECHNICAL-ARCHITECTURE.md)
3. [Engineering Plan](docs/03-ENGINEERING-PLAN.md)
4. [Security and License Policy](docs/04-SECURITY-LICENSE-POLICY.md)
5. [UI/UX System](docs/05-UI-UX-SYSTEM.md)
6. [Performance and Quality](docs/06-PERFORMANCE-QUALITY.md)
7. [Project Data Model](docs/07-PROJECT-DATA-MODEL.md)
8. [Build Toolchain](docs/08-BUILD-TOOLCHAIN.md)
9. [Third-Party Component Audit](docs/09-THIRD-PARTY-COMPONENT-AUDIT.md)

## Repository boundaries
The previous `oss-mobile` repository is retained as an archived/reference project. This repository is the clean restart; existing code will only be reused after compatibility, license, security, and architecture review.

## Development
Implementation decisions and dependency versions are pinned and documented before adoption. See the engineering plan for staged delivery and acceptance gates.
