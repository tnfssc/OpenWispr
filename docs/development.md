# Development

Clone with submodules when working on the Android keyboard:

    git clone --recurse-submodules https://github.com/tnfssc/OpenWispr.git

Build commands run from the relevant app directory:

    cd apps/macos && ./scripts/check.sh
    cd apps/gnome && go test ./...
    cd apps/android-keyboard && ./gradlew assembleUnstableDebug

The Android keyboard's submodules are large. Contributors working only on macOS or GNOME should use sparse checkout or initialize only the submodules they need.

Do not modify the Android keyboard's upstream-derived code without reading [the upstream maintenance notes](upstreams/futo-keyboard.md).
