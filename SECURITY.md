# Security

Ferry carries people's private messages, so security reports are taken seriously.

## Reporting a vulnerability

Please **don't open a public issue**. Report it privately instead:

1. Go to the repository's **Security** tab.
2. Click **Report a vulnerability**.

Include what's affected (Android, iOS, server or protocol), the version, and steps to reproduce.
You'll get a reply within a few days. Once a fix ships, you'll be credited in the advisory
unless you'd rather not be.

## In scope

- Anything that lets the server, the push service or a network attacker read message contents
- Breaking or bypassing the encryption, pairing or device revocation
- A revoked device that can still fetch new messages
- Leaks of keys or decrypted messages on the device (logs, backups, other apps)
- Server bugs that expose one account's data to another

The threat model, including what's knowingly out of scope, is in
[docs/PROTOCOL.md §1.1](docs/PROTOCOL.md#11-threat-model).
