# Security policy

Thank you for helping keep CameraCtrl and its users safe.

## Reporting a problem

**Please don't report security problems in a public issue.** Report them privately instead:

1. Go to the [Security tab](https://github.com/VoidedKN0X/CameraCtrl/security) of this repo.
2. Click **Report a vulnerability** and describe what you found, how to reproduce it, and which version of CameraCtrl you used.

Only the maintainer can see the report. I'll reply as soon as I can, work on a fix, and credit you in the changelog if you'd like.

## Supported versions

Only the latest release gets security fixes. Please update before reporting, and check the [changelog](CHANGELOG.md) to see whether it's already fixed.

## What counts

CameraCtrl has no server and no internet permission: everything stays on your phone. So these count:

- Any way for CameraCtrl to send photos, videos or their details off the phone
- The camera or microphone staying in use after you leave CameraCtrl
- Location or other personal data ending up in saved files
- Ways around the app lock, or protected screens showing up in screenshots when they shouldn't
- Weaknesses in the app lock PIN
- Anything that lets someone run code or read files they shouldn't

## How releases are protected

Every release is built from this repo by GitHub Actions and signed with the same release key. Its SHA-256 certificate fingerprint is:

```
EC:F6:89:5F:2D:0F:7B:C9:1C:12:11:14:DB:3B:22:18:D5:8C:B4:04:48:F7:79:7D:A0:4C:5E:C5:1F:04:1A:1D
```

The key never meets the build: the job that runs Gradle (and with it every plugin and library) has no secrets and produces an unsigned APK. A separate job that checks out nothing and runs no build code signs that APK, then refuses to pass it on unless it is signed by exactly this certificate and its contents are unchanged.
