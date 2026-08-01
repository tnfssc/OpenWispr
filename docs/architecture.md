# Architecture

OpenWispr has independent native clients rather than one cross-platform application:

- apps/macos: Swift menu-bar application.
- apps/gnome: GNOME Shell extension plus Go companion user services.
- apps/android-keyboard: a modified FUTO Keyboard distribution with OpenWispr voice input.

The clients share a product contract—dictation flow, configurable transcription providers, optional cleanup, and explicit privacy messaging—but not a source-code library. Platform releases and compatibility are intentionally independent.
