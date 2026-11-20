# Security Policy

## Reporting a Vulnerability

If you find a security issue in Pendant Companion, please report it privately rather than opening a public issue.

- Use GitHub's "Report a vulnerability" button under the Security tab of this repository, or
- Email John Marrone via marronejohn@users.noreply.github.com.

Please include a description of the issue, steps to reproduce it, and your assessment of its impact. You can expect an initial response within a few days.

## Supported Versions

Only the latest release (the current 0.15.x APK and its source) is supported with security fixes.

## Scope

Pendant Companion is a privacy-first Android companion app for the Limitless/Omi pendant: it pairs over Bluetooth LE, stores transcripts locally, and can send text to an AI provider using the user's own API key (bring-your-own-key). Reports are most useful for:

- Anything that mishandles transcript data, audio, or local storage
- Bluetooth pairing or permission misuse, or privacy issues
- Anything that could leak the user's API key or other credentials
- Anything that could let a third party execute code on a user's device

Issues in vendored third-party components should be reported upstream.
