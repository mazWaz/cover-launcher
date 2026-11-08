# Matriks fitur: OpenCover vs Cover Launcher

Inventaris rinci dari source OpenCover (`1fb97ca`, 13.765 LOC) yang dipetakan ke repo ini.
Kolom **Kita** = keadaan `com.mazwas.coverlauncher` hari ini. Dokumen prioritas: [PARITY-OPENCOVER.md](PARITY-OPENCOVER.md).

## A. Mekanisme (yang paling menentukan, dan paling banyak miss)

> Setting `multistar_setting_repository` adalah milik sistem, bukan milik OpenCover, jadi isinya kemungkinan
> tetap ada walau OpenCover di-uninstall (belum diuji). Untuk app yang ada di daftar itu, jalur fallback kita
> (`startActivity` + `coverOptions()`, tanpa Shizuku) seharusnya diizinkan Samsung — juga belum diuji.

Terverifikasi di HP target (`adb`, 2026-11-08):

| Cek | Hasil |
|---|---|
| `settings get secure multistar_setting_repository` | ada, format slot: 25 × `0/` lalu slot 26 `backup,0;com.android.chrome,0;com.android.settings,0;…` — 35 entri |
| `dumpsys package dev.opencover \| grep WRITE_SECURE_SETTINGS` | `granted=true` (allowlist memang dipakai di HP ini) |
| `cmd appops get dev.opencover RUN_ANY_IN_BACKGROUND` | `allow` |
| `cmd phone get-allowed-network-types-for-users -s 0` | `No valid subscription found` — toggle 5G butuh SIM aktif |

| Mekanisme | OpenCover | Kita |
|---|---|---|
| Daftar app boleh tampil di cover tanpa shell | **Ya**: menulis semua app ke secure setting `multistar_setting_repository` slot 26 (`"pkg,userId;…"`), yang dibaca MultiStar. Shizuku hanya fallback. | Ya, sebagai cadangan: `Grants` menambah semua app ke slot 26 (hanya menambah, backup di `/data/local/tmp`); launch utama tetap `am start --display 1`. Teruji tanpa Shizuku (A/B) |
| Proses yang menjaga cover | Foreground service `CoverWatcher` (`specialUse`): display change, `USER_PRESENT`, heat guard, overlay | Tidak ada service; hanya accessibility service |
| Hidup lagi setelah boot/update | `BootReceiver` (`BOOT_COMPLETED` + `MY_PACKAGE_REPLACED`) → `startForegroundService` | Andalkan accessibility service + binder Shizuku |
| Melawan Samsung yang menimpa state lipat | `Fold.kt`: watcher shell membaca logcat `DeviceStateManagerService`, minta ulang state `1` (TENT) saat "Committing state" 0\|3 | Penjaga shell `coverlauncher-fold`: logcat `onStateChanged` → `state 0` lagi (override hilang tiap HP dilipat) |
| Start activity dari background (Android 15+) | Overlay 1×1 di display 1 (`BAL_BLOCK` tidak berlaku) + `KeepAwake` | Appop `SYSTEM_ALERT_WINDOW` + a11y |
| Dialog "Allow USB debugging?" di layar mati | Tombol di Settings: a11y menekan **Allow** pada dialog itu | Dialog aslinya dipindah ke cover (`pullToCover`), pengguna menjawab sendiri; belum diuji dengan dialog asli |
| Scope a11y | Dinamis: `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` hanya saat top-edge/lock/nav, `FLAG_INPUT_METHOD_EDITOR` saat mengetik | Statis, semua window |
| Activity sendiri tampil di cover | meta-data `com.samsung.android.multiwindow.show_on_cover=true` | Tidak dipakai (semua lewat shell) |
| Shizuku hidup setelah reboot | Manual (Start tiap boot) | **Otomatis** ±90 s (tekan Start lewat a11y) |
| Tekan tombol UI app lain | Hanya dialog sistem "Allow" (debugging) | Ya, tombol Start Shizuku (`ShizukuAutoStart`) |

## B. Home & tampilan

| Fitur | OpenCover | Kita |
|---|---|---|
| Gaya clock | Current, Big, Stacked, Analog, At a Glance | Satu gaya (Doto) |
| Widget | Battery, next alarm, cuaca (Open-Meteo, opt-in, INTERNET) | Tidak ada |
| Layout | Grid 4×4, item/widget di mana saja, long-press → move/resize/hide, 4 quick slot | Dock 4 app tetap |
| Wallpaper | Foto, GIF/WebP animasi (dijadikan video), 4 gaya panel (frosted, scrim, tint, floating), crop + adjust | Foto statis + filter halftone dot-matrix |
| Ikon | Icon pack Nova/ADW/GO, 6 bentuk ikon ala Lawnchair, Lawnicons, themed satu warna dari wallpaper | Mono/color (monochrome layer Android) |
| Bar navigasi | Punya sendiri: Home/Recents/Back, pill, tombol sembunyikan keyboard, `dropCurtainIfHome` | Nav bar Samsung |
| Transisi buka app | Kartu tumbuh dari ikon (animasi Compose sendiri) lalu app fade-in (`makeCustomAnimation`); hanya untuk app di allowlist | Tidak ada (shell tanpa animasi) |

## C. App drawer & paket

| Fitur | OpenCover | Kita |
|---|---|---|
| Multi-user (profil kerja / Secure Folder) | Ya: `LauncherApps` per user, `am start --user <id>`, ikon per user | Tidak: `Process.myUserHandle()` saja |
| Urutan | A–Z atau terakhir dipakai (`Sort.AZ` / `Sort.RECENT`) | A–Z + pencarian |
| App info / uninstall / pin | Tidak ada (hanya "add to home") | **Ya**: long-press → pin, app info, uninstall |
| Pencarian | Ya | Ya |

## D. Notifikasi

| Fitur | OpenCover | Kita |
|---|---|---|
| Aksi (maks 3 tombol) | Ya (`Notification.Action`) | Ya; Balas membuka app, panggilan tanpa tombol |
| Balas teks | `ReplyActivity` + `RemoteInput` | Tidak |
| Balas suara | `SpeechRecognizer` di ReplyActivity | Tidak |
| Pop-up di atas app | Banner overlay di display 1 (bisa dimatikan) | **Ya** (opt-in): kartu panel di overlay a11y display 1, 5 s; pop-up Samsung dimatikan selama itu (`edge_lighting`, `cover_screen_show_notification`). Teruji dengan pesan WhatsApp |
| Peek dari lock screen → buka setelah unlock | `LockScreen.pendingOpen` + `openOnUnlock` | Sebagian: ketuk pop-up saat cover terkunci → launcher membuka kunci lalu notifikasinya (belum diuji) |
| Daftar, dismiss, clear all, pull-to-clear | Ya | Ya (kecuali pull-to-clear) |
| Edge lighting | Overlay glow, warna per app, wake→glow→sleep | Tidak |

## E. Media

| Fitur | OpenCover | Kita |
|---|---|---|
| Panel media | Ya: multi-sesi, sampul, seek di tepi, volume di tepi, shuffle/repeat, layout klasik | Ya, minimal: sesi teratas, judul + artis, ⏮ ⏯ ⏭ |
| Chip now-playing di AOD/lock | Ya | Tidak |

## F. Quick settings

| Fitur | OpenCover | Kita |
|---|---|---|
| Tile | 15: Wi-Fi, BT, senter, Rotate, sound, DND, saver, location, data, airplane, NFC, dark mode, keep awake, mirror, hotspot | 13: Wi-Fi, BT, data, senter, DND, airplane, sound, location, saver, NFC, dark mode, keep awake, rotate/locked (5 tampil, usap bawah = semua) |
| Brightness cover | Slider (`system/sub_screen_brightness`, 1..255, Shizuku) | Ya: slider + AUTO (`…_mode`), lewat Shizuku |
| Susun tile | Drag & drop, tambah/hapus, urutan disimpan | Tidak |
| Long-press tile | Halaman/popup sistem | Ya (Wi-Fi, internet, volume, halaman settings) |

## G. Display & orientasi

| Fitur | OpenCover | Kita |
|---|---|---|
| Rotasi per app | Ya (`cmd window user-rotation -d 1 lock N` + `fixed-to-user-rotation`) | Sebagian: hanya terbalik 180° per app (drawer → *upside down*), plus tile rotate dari sensor orientasi |
| Density per app | Ya (preset 300/260/220/400 dpi) | Tidak |
| Density/font/animation global | Ya | Tidak |
| Ukuran/font layar dalam | Untuk mirror saja | Tidak (tidak relevan) |

## H. Lock screen, AOD, panggilan

| Fitur | OpenCover | Kita |
|---|---|---|
| AOD sendiri (jam, ikon notif, chip now-playing, timer mati, burn-in shift) | Ya (butuh AOD Samsung "Always", tap-to-show off) | Tidak (AOD Samsung) |
| Lock screen sendiri + layout drag | Ya, swipe up → PIN Samsung | Tidak |
| "Fingerprint opens Home" | Ya (secure settings) | Tidak |
| Layar panggilan (answer, decline, answer muted, mute 30 menit) | Ya (notifikasi panggilan + `TelecomManager` + `ANSWER_PHONE_CALLS`) | Tidak (UI telepon Samsung) |
| Panggilan internet (WA/Messenger) | Eksperimental | Tidak |

## I. Baterai & sistem (semua lewat Shizuku)

| Fitur | OpenCover | Kita |
|---|---|---|
| Deep sleep dipaksa saat idle | Ya (`dumpsys deviceidle force-idle deep` lewat Shizuku; sensor gerak mati = tidak pernah doze sendiri) | Tidak |
| 5G on/off | Ya (`cmd phone get/set-allowed-network-types-for-users -s 0`) | Tidak |
| Restrict app di background | Ya (`cmd appops RUN_ANY_IN_BACKGROUND ignore` + `am set-standby-bucket restricted`) | Tidak |
| Heat guard (42 °C peringatan, 45 °C matikan hotspot/mirror) | Ya, jalan di FGS | Tidak |
| Power saving Samsung mematikan AOD → notifikasi + tombol | Ya | Tidak |
| Keep awake di app apa pun | Ya (overlay + `FLAG_KEEP_SCREEN_ON`) | Ya (tile; overlay a11y 1×1 di cover + `FLAG_KEEP_SCREEN_ON`) |
| Location scanning / Google Location Accuracy | Ya | Tidak |

## J. Mirror & desktop (monitor eksternal)

| Fitur | OpenCover | Kita |
|---|---|---|
| Mirror layar dalam ke cover | Ya: `MediaProjection`, sentuh multi-jari, resize layar dalam, matikan developer options + unknown sources selama sesi | Tidak |
| Desktop di monitor USB-C | **Ya (PoC)**: OpenCover menutup mirror Samsung dengan window sendiri, `DeskService` membuat *trusted virtual display* lewat Shizuku, DeX jalan di situ; taskbar, kursor mouse, recents, game, latar animasi | Tidak |

Catatan: desktop di monitor tetap berguna walau panel dalam mati — output-nya ke monitor, bukan ke layar HP.

## K. Keyboard

| Fitur | OpenCover | Kita |
|---|---|---|
| IME sendiri di cover (QWERTY, saran kata, swipe, long-press popup) | Ya (eksperimental, off by default) | Tidak (keyboard Samsung) |

## L. Setup & diagnosa

| Fitur | OpenCover | Kita |
|---|---|---|
| Layar "Setup & permissions" (daftar apa yang kurang + tombol) | Ya | Sebagian: tiga indikator (Shizuku, notifikasi, cover service) + "Fix permissions" |
| Banking apps picker (a11y berhenti saat app itu dibuka) | Ya + `WRITE_SECURE_SETTINGS` untuk menyalakan lagi | Tidak |
| Buka widget cover Samsung | Ya | Tidak |
| Approve debugging prompt | Ya | Ya, dialog dipindah ke cover (belum diuji) |
| Experimental: desktop, keyboard, internet calls | Ya | Tidak ada bagian eksperimental |

## Yang kita punya dan OpenCover tidak

| Kita | OpenCover |
|---|---|
| Auto-Start Shizuku ±90 s setelah boot, termasuk tekan Start di UI-nya | manual tiap reboot |
| Pin/app info/uninstall dari drawer | hanya "add to home" |
| Wallpaper halftone (shader GPU) + desain Nothing | foto/GIF apa adanya |
| `pullToCover()` untuk task yang nyangkut di display 0 | `KeepOnCover` (mirip, per window) |
| Paksa `device_state 0` setiap binder datang | opsi "keep cover on" |
| Status row sendiri (ikon alert per app, DND/BT/Wi-Fi, baterai segmented) | tidak ada status row; baterai hanya widget chip opsional |

## Ringkas angka

| | OpenCover | Kita |
|---|---|---|
| LOC | 13.765 (116 file) | ±2.550 (31 file) |
| Paket fitur | 18 (+ `ui/`) | 3 (`cover`, `launcher`, `settings`) + modul `covercam` |
| Unit test | 39 file | lihat `app/src/test` |
| Izin manifest | 20 (INTERNET, NFC, VIBRATE, ANSWER_PHONE_CALLS, READ_PHONE_STATE, PACKAGE_USAGE_STATS, FGS×3, RECEIVE_BOOT_COMPLETED, WRITE_SECURE_SETTINGS, …) | 6 (SYSTEM_ALERT_WINDOW, QUERY_ALL_PACKAGES, REQUEST_DELETE_PACKAGES, ACCESS_WIFI_STATE, ACCESS_NETWORK_STATE, NFC) |
| Service | FGS watcher + a11y + notification listener + mirror service | a11y + notification listener |
| Receiver | boot/update | tidak ada |
