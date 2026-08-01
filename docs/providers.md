# Providers and privacy

Clients may support local transcription and user-configured remote providers. The exact provider and model choices vary by platform and are documented in each client.

Before enabling a remote provider, users must understand that recorded audio and, when enabled, transcript cleanup requests are sent to that provider. Keyboard typing, prediction, and swipe processing remain local to the Android keyboard. No client should imply that a remote provider is private or local.

When changing a provider integration, keep these expectations aligned across supported clients:

- explain where audio and transcript text go;
- require the user to supply credentials where applicable;
- make automatic paste and clipboard behavior configurable;
- preserve a clear cancellation path before text insertion.
