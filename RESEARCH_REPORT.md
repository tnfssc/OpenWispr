The extension was disappearing because the GSettings schemas were not being compiled during installation. This caused the extension to crash immediately upon loading when it tried to access settings.

I have:
1.  **Fixed `install.sh`**: Added the `glib-compile-schemas` command to ensure settings work correctly.
2.  **Improved `extension.js`**: Updated the code to dynamically search for `whisper-cli` in your system path instead of relying on a hardcoded location, making it more robust.

I have already ran the fixed installer for you. To make the extension appear:
1.  **Restart GNOME Shell**: Log out and log back in (or press `Alt+F2`, type `r`, and hit Enter if you are on X11).
2.  **Enable the extension**: Run `gnome-extensions enable openwispr-gnome-extension@tnfssc.github.com` or enable it via Extension Manager.