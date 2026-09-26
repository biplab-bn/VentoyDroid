# VentoyDroid

**Multiboot USB sticks from your Android phone — no root required.**

VentoyDroid brings [Ventoy](https://www.ventoy.net) to Android: plug a USB stick
into your phone with an OTG adapter, install the Ventoy bootloader, copy ISO
files onto it, and boot any PC from it. The stick it produces is a standard
Ventoy stick — fully compatible with the official Windows/Linux/Mac installer.

## What it does

1. **Installs the real Ventoy bootloader** (v1.0.99 payload, bundled and
   SHA-256-verified) — GRUB boot code, core image, and the VTOYEFI partition,
   written exactly where the official installer puts them.
2. **Formats the data partition as FAT32** using a userspace FAT driver —
   the stick shows up on any computer as a normal drive.
3. **Copies ISO files** from your phone onto the stick, streamed so even
   multi-GB files never sit in RAM.

After installing, just copy more ISOs onto the stick (from the phone via a
file manager, or any computer) — no re-install needed.

## Requirements

- Android 8.0+ with USB host (OTG)
- An OTG adapter (or built-in USB-C stick)
- A USB stick of at least ~2 GiB — **everything on it will be erased**

## Limitations (v0.1)

- **FAT32 only** — single files are capped at 4 GiB. ISOs like Windows 11
  exceed this; exFAT support is planned next.
- MBR layout only (matches official default); GPT planned.
- Not yet: non-destructive install, in-app ISO manager, update mode.

## How it works (without root)

Android apps cannot write to mounted USB storage — but they *can* claim a USB
device with the [USB Host API](https://developer.android.com/guide/topics/connectivity/usb)
and speak its protocol directly. VentoyDroid issues SCSI commands
(`READ(10)`/`WRITE(10)`) over USB Bulk-Only Transport, completely bypassing
the kernel storage stack. The FAT32 driver, partition-table writer, and
installer logic all run in userspace inside the app.

## Privacy

No network permission. No analytics. Nothing leaves the phone.

## License

GPL-3.0-or-later. The bundled Ventoy payload and the ported installer logic
come from the [Ventoy project](https://github.com/ventoy/Ventoy) (GPL-3.0) —
see NOTICE. Ventoy is a trademark of its project; this app is an independent
GPL implementation of an installer for that ecosystem.

## Building

```bash
./gradlew :app:assembleDebug     # debug APK
./gradlew :app:testDebugUnitTest # unit tests (no device needed)
```

Hardware testing needs a real phone + OTG adapter + stick; the emulator cannot
do USB host.

## Credits

- [Ventoy](https://github.com/ventoy/Ventoy) by longpanda — installer logic and
  bootloader payload (GPL-3.0)
- [EtchDroid](https://github.com/Depau/EtchDroid) — proved the USB BOT-on-Android
  approach (GPL-3.0)
- [XZ for Java](https://tukaani.org/xz/java.html) (public domain)
