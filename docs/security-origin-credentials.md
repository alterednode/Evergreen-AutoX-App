# Origin-scoped iOS credentials

Device tokens, Apple metadata, saved poster names, and hidden-board preferences
are separated by scheme, lowercase hostname, and effective port. Only the exact
https://autox.romangarms.com:443 origin inherits the legacy credential.
Server changes clear visible state and discard responses from a previous selected
server. API requests reject malformed base URLs and refuse redirects.

Tokens remain long-lived. This change does not revoke a previously leaked token.
Sign-out unlinks it temporarily; signing back in with the same token can restore
access to anyone holding a copy. Permanent recovery requires server-side token
revocation or rotation, which is not implemented by this branch.

The isolated Swift harness runs via `pytest tests/test_ios_identity.py` when swiftc
is installed. Native Keychain persistence/failure handling, redirects, server
switching during requests, sign-out, and account deletion still require macOS/iOS
validation. No Xcode or Swift compiler is available in the development host.

This branch contains finding 1 only; it has no server review, parser, storage,
admin-session, dependency, or container changes.
