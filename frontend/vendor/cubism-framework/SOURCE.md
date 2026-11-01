# Pinned official Live2D Cubism Web Framework

- Release: **5-r.4**, official tag commit `8df84780f2aa1298f3b30965cdae143e049f3c8e`.
- Source: https://github.com/Live2D/CubismWebFramework/tree/5-r.4
- Distribution: https://cubism.live2d.com/sdk-web/bin/CubismSdkForWeb-5-r.4.zip
- Archive SHA-256: `d78904d908bd232b800219e01732e4ea2f0562b5e9f35a2670742a1c16d22942`.
- `src/`, LICENSE.md, README.md and CHANGELOG.md are unmodified official distribution files. No samples/models or Luna bundle are vendored.
- `tsconfig.build.json` is BigFish's separate build configuration, using the already pinned project TypeScript compiler. No dependency version upgrade.
- License: Live2D Open Software License, **not MIT**; retain LICENSE.md and source notices. Publication/SDK release licensing must be reviewed by the integration owner before distribution.
- `../cubism-core-types/` contains official compile-time declarations and their license only. The proprietary executable Core is never imported or bundled.
- External Core: SDK 5-r.4 / 05.01.0000, SHA-256 `25ae938cb4fe282ce189b357bcc97e603d1e1f7ec78bf04150d401c23cdc792f`; local `/avatar-assets/sdk/live2dcubismcore.min.js` only.
- Official terms: https://www.live2d.com/eula/live2d-open-software-license-agreement_en.html and https://www.live2d.com/eula/live2d-proprietary-software-license-agreement_en.html
