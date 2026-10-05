# Contributing to Qingyu

Thank you for helping make Chinese typing feel natural and English easier to notice along the way.

## Before opening an issue or pull request

- Search existing issues first. For a bug, include reproducible steps, Android version, device model or emulator ABI, and what happened versus what you expected.
- Never include passwords, private messages, personal dictionary contents or other private typing data in an issue, log or screenshot.
- For dictionary suggestions, provide the Chinese entry, proposed short English gloss and a reputable source. Do not submit copied dictionary datasets without checking their license and provenance.

## Development

The current supported build environment is Windows with JDK 17 and PowerShell 7. See the setup and build commands in [README.md](README.md#从源码构建), and run the focused Android checks on an emulator before a pull request when possible. Explain any checks you could not run.

Keep Chinese input independent of translation work. Gloss lookup, error handling and cache changes must not block candidate generation or committing text. Avoid adding network access, analytics or collection of typed content.

## Licensing contributions

By submitting original Qingyu code, you offer that contribution under GNU GPL-3.0-only, consistent with the project. Keep third-party material clearly identified and under its own terms. Do not remove upstream copyright, attribution, license or NOTICE text. For substantial third-party changes, update the matching source and license records.

Pull requests should describe the user-visible change, link any related issue, and note the Android version and build or test results.
