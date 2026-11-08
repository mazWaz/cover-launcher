# Penerapan di HP

Checklist operasional: pasang, nyalakan, **verifikasi tiap bagian**, dan cara mundur. Prosa instalasi ada di
[README](../README.md); dokumen ini yang dipakai saat benar-benar menerapkan.

Target: Galaxy Z Flip5 (SM-F731B), cover `display 1` 748×720, layar utama boleh mati.

## 0. Prasyarat

| Butuh | Cek |
|---|---|
| `adb` di komputer | `adb version` |
| JDK 17+ | `java -version` |
| Android SDK platform 37 | `ls $ANDROID_HOME/platforms` |
| Wireless/USB debugging aktif di HP | `adb devices -l` → perangkat `device` |
| Shizuku terpasang | `adb shell pm list packages \| grep shizuku` |

Wireless debugging (tanpa kabel): `adb mdns services` → pakai port `_adb-tls-pairing._tcp`, lalu
`adb pair <ip>:<port> <kode>`; kode pairing sekali pakai dan port berubah setiap toggle.

## 1. Build

```sh
./gradlew :app:assembleRelease
```

Jangan pakai build debug: Compose debug janky di cover.

## 2. Install

```sh
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell cmd package compile -m speed -f com.mazwas.coverlauncher   # opsional, terasa lebih halus
```

## 3. Shizuku

Harus hidup **sebelum** launcher dipakai; tanpa itu app tetap terbuka tapi bisa kena
"Open your phone to continue".

- Dari HP: Shizuku → *Start via Wireless debugging* → Start.
- Dari komputer: Shizuku → *Start via connecting to a computer* → *View command*, jalankan perintah yang
  ditampilkan. Jangan menebak path starter: di Shizuku 13.6 HP ini `lib/arm64/` hanya berisi `libadb.so` dan
  `librish.so` (`libshizuku.so` tidak diekstrak).
- Set *Settings → Apps → Shizuku → Battery → Unrestricted*.

## 4. Buka pertama kali

```sh
adb shell am start --display 1 -n com.mazwas.coverlauncher/.feature.launcher.presentation.LauncherActivity
```

Shizuku minta izin → **Allow**. Setelah itu `Grants` memasang semuanya sendiri (state lipat, overlay, listener,
DND, accessibility). Kunci lalu bangunkan cover: harus mendarat di launcher, bukan jam Samsung.

## 5. Verifikasi

Semua perintah di bawah dijalankan dari komputer. Format keluaran di kolom kanan adalah contoh nyata dari HP.

| Yang dicek | Perintah | Harapan |
|---|---|---|
| State lipat dipaksa | `adb shell cmd device_state print-state` | `0` (CLOSED) |
| Boleh start dari background | `adb shell appops get com.mazwas.coverlauncher SYSTEM_ALERT_WINDOW` | `SYSTEM_ALERT_WINDOW: allow` |
| Accessibility hidup | `adb shell settings get secure enabled_accessibility_services` | mengandung `com.mazwas.coverlauncher/com.mazwas.coverlauncher.feature.cover.CoverService` |
| Accessibility saklar utama | `adb shell settings get secure accessibility_enabled` | `1` |
| Listener notifikasi | `adb shell settings get secure enabled_notification_listeners` | mengandung `com.mazwas.coverlauncher/com.mazwas.coverlauncher.feature.launcher.data.NotificationListener` |
| Notifikasi benar-benar masuk | `adb shell cmd notification list \| head` | ada baris notifikasi aktif |
| Shizuku siap | buka launcher → long-press home → **Shizuku: ready** | `ready` |
| Cover service | layar Settings yang sama → **cover service** | `on` |
| Cover home ditimpa | tekan Home/Back di cover sampai ke jam Samsung | launcher kembali sendiri |
| App terbuka di cover | buka app dari dock | muncul di cover, bukan di layar mati |

Kalau satu baris gagal: jalankan **Fix permissions** di Settings launcher (menjalankan ulang `Grants.apply()`),
setelah memastikan Shizuku `ready`.

## 6. Setelah reboot

Otomatis: accessibility service hidup lagi → `App` menerima binder Shizuku → `Grants` dipasang ulang;
sekitar 90 detik setelah boot launcher membuka Shizuku dan menekan **Start** sendiri.

Yang bisa gagal dan penanganannya:

| Gejala | Sebab | Tindakan |
|---|---|---|
| Shizuku tetap `off` | UI Shizuku bukan bahasa Inggris, atau pairing wireless debugging hilang | buka Shizuku, Start manual; pairing ulang bila perlu |
| Apps balik ke "Open your phone" | Shizuku belum hidup | Start Shizuku, buka app lagi |
| Cover bangun ke jam Samsung | accessibility service mati | Settings → Fix permissions, atau nyalakan manual di Accessibility |
| Layar utama menyala saat HP dibuka | state lipat di-reset (mis. sesudah update One UI) | `adb shell cmd device_state state 0` |

## 7. Mundur

```sh
adb uninstall com.mazwas.coverlauncher
adb shell cmd device_state state reset      # kembalikan layar utama
```

Izin yang dipasang lewat shell (overlay appop, listener, a11y, DND) hilang bersama paketnya, kecuali
entri a11y yang tersisa di `enabled_accessibility_services` pada sebagian build — bersihkan dengan:

```sh
adb shell settings put secure enabled_accessibility_services "$(adb shell settings get secure enabled_accessibility_services | tr -d '\r' | sed 's#com.mazwas.coverlauncher[^:]*##; s#::#:#g; s#^:##; s#:$##')"
adb shell settings put secure enabled_notification_listeners "$(adb shell settings get secure enabled_notification_listeners | tr -d '\r' | sed 's#com.mazwas.coverlauncher[^:]*##; s#::#:#g; s#^:##; s#:$##')"
```

## 8. Kalau HP dipakai tanpa keeper

Launcher memaksa state `CLOSED` lewat Shizuku setiap kali binder muncul, jadi biasanya tidak perlu skrip
tambahan. Kalau layar malah mati saat HP dibuka (override state terlempar oleh layanan Samsung), pilihan
cepat: lipat HP lagi, atau jalankan `cmd device_state state 0` manual. Skrip keeper di `/data/local/tmp`
di luar repo ini dan tidak dijalankan otomatis.
