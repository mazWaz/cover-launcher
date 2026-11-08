# Gap vs OpenCover

Pembanding: [Shmubbier/OpenCover](https://github.com/Shmubbier/OpenCover) (GPL-3.0) — launcher FOSS untuk
cover Z Flip5. Analisis di bawah dari **source**, bukan README: commit `1fb97ca` (2026-11-06, sesudah tag
`v0.2`), 116 file Kotlin di `app/src/main`, 13.765 LOC, 18 paket fitur (+ `ui/`), 39 file unit test.
Repo ini ±2.550 LOC (modul `app`, tanpa test).

Angka LOC di tabel = ukuran paketnya di OpenCover, patokan kasar biaya fitur itu — bukan biaya kita.

Inventaris lengkap per fitur (mekanisme, home, drawer, notifikasi, media, quick, display, lock, baterai, mirror/desk,
keyboard, setup): [FEATURE-MATRIX.md](FEATURE-MATRIX.md).

Status perangkat: `com.mazwas.coverlauncher` belum terpasang; yang jalan `dev.opencover` 0.2.

## Ringkasan

| Angka | Nilai |
|---|---|
| OpenCover total | 13.765 LOC, 116 file, 18 paket, 39 file test |
| Relevan untuk HP panel-mati | ±1.788 LOC: `notify/` 430, `quick/` 409, `media/` 215, `edge/` 216, `phone/{DisplayTweaks,Rotation,RotationFeature,AppDensity,PhonePrefs}` 335, `watcher/{KeepAwake,BankingMode,BankingRules}` 183 |
| Tiga prioritas (aksi notifikasi, media, tile+brightness) | ±700 LOC |
| Sengaja tidak diambil | `mirror/` 858, `keyboard/` 721, `lock/` 555, `nav/` 525, `call/` 448, `home/`+`layout/`+`widgets/`+`look/` ±1.100 |
| Repo ini sebagai pembanding | ±2.550 LOC (modul `app`, tanpa test) |
| Mekanisme inti yang belum ada | daftar app ke cover lewat MultiStar (`multistar_setting_repository`), foreground service + boot receiver, overlay 1×1 (BAL/keep-awake), tombol approve dialog debugging |

Artinya selisih fungsionalnya kecil: yang hilang dari cover-launcher adalah lapisan pemakaian
(aksi notifikasi, media, brightness/tile, density/rotasi), bukan arsitektur. Tiga prioritas pertama
tidak menambah izin, tidak menambah paket baru, dan memakai `NotificationStore` /
`SystemStatusSource` / `SettingsStore` yang sudah ada.

## Yang sudah setara

Home + wallpaper + 4 shortcut, app drawer + pencarian, daftar notifikasi (buka, swipe dismiss, clear all),
quick settings, auto-buka saat cover di-unlock, Samsung Home kembali ke launcher, pemaksaan cover,
tanpa izin internet.

## Fitur yang belum ada

| Fitur | Di OpenCover | API inti | Effort kita |
|---|---|---|---|
| Aksi notifikasi (maks 3 tombol) | `notify/` 430 LOC; `Notification.Action` → `NoteAction(title, acceptsReply)` | `SBN.notification.actions` + `PendingIntent.send` | S |
| Balas teks/suara | `ReplyActivity`: `RemoteInput.addResultsToIntent` + `SpeechRecognizer` | `RemoteInput`, `RecognizerIntent` | M — isi teks butuh keyboard (baris keyboard) |
| Heads-up di atas app mana pun | `notify/Banner.kt` | `createDisplayContext(cover).createWindowContext(TYPE_APPLICATION_OVERLAY)` di display 1 | M |
| Media player | `media/` 215 LOC: `MediaSessionManager.getActiveSessions(NotificationAccess)` + `MediaControllerCompat`, multi-sesi, shuffle/repeat/seek | izin notification access yang sudah kita pegang | S |
| Brightness cover | `quick/QuickState.kt`: `settings get\|put system sub_screen_brightness` (1..255) | **kunci Samsung sendiri, lewat Shizuku** | S |
| 15 tile + drag reorder | `quick/Tiles.kt`: Wi-Fi, BT, senter, Rotate, sound, DND, saver, location, data, airplane, NFC, dark mode, keep awake, mirror, hotspot; urutan = nama enum | `Need` per tile: Shizuku / secure settings / notification access | S |
| Rotasi per app | `phone/Rotation.kt` + `RotationFeature.kt` + `PhonePrefs` | `cmd window fixed-to-user-rotation -d 1 enabled; cmd window user-rotation -d 1 lock N` | M |
| Density, font, animation scale | `phone/DisplayTweaks.kt` 58 LOC | `wm density -d 1`, `settings put system font_scale`, 3 × `*_animation_scale` | S |
| Edge lighting per app | `edge/` 216 LOC: overlay glow, warna per app, opsi wake→glow→sleep | overlay window + `wakeUp` | M |
| Keep awake di app apa pun | `watcher/KeepAwake.kt`: overlay 1×1 di display 1 + `FLAG_KEEP_SCREEN_ON` | overlay sekaligus memenuhi syarat BAL Android 15+ | S |
| Banking: a11y dimatikan sementara | `watcher/BankingMode` + `BankingRules` (usage events) | `Settings.Secure.ACCESSIBILITY_ENABLED` (izin `WRITE_SECURE_SETTINGS`, di-grant lewat adb) | S |
| Panggilan di cover | `call/` 448 LOC: `Notification.EXTRA_ANSWER_INTENT`, fallback `TelecomManager.acceptRingingCall` | notifikasi panggilan + PendingIntent | M |
| AOD + lock screen sendiri | `lock/` 555 LOC: overlay, cek AOD Samsung siap, layout drag | overlay di atas keyguard | L |
| Keyboard sendiri | `keyboard/` 721 LOC: `InputMethodService`, saran kata, swipe | IME penuh | L |
| Home: widget, gaya clock, grid drag/resize | `home/` 675 + `layout/` 246 + `widgets/` 104 + `look/` 88 | Compose + Glance | M–L |
| Nav bar sendiri (Home/Recents/Back, pill) | `nav/` 525 LOC | overlay + `performGlobalAction` | M |
| Mirror layar dalam | `mirror/` 858 LOC | `MediaProjection`, resize layar dalam | **nol** — panel dalam mati |
| Desktop di monitor USB-C | `desk/` 1.451 LOC (PoC): window sendiri menutup mirror Samsung, `DeskService` membuat *trusted virtual display* lewat Shizuku, DeX jalan di situ, plus taskbar/kursor/game | `DisplayManager.createVirtualDisplay` sebagai shell | L — **bukan nol**: panel mati tidak dipakai, output ke monitor |
| Daftar app ke allowlist cover | `apps/CoverAllowList.kt`: tulis semua app ke `multistar_setting_repository` slot 26 | `Settings.Secure.putString` (butuh `WRITE_SECURE_SETTINGS`) | M |
| Foreground service + boot receiver | `watcher/CoverWatcher` (`specialUse`) + `BootReceiver` | `startForegroundService`, `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED` | M |
| Approve dialog "Allow USB debugging" | `CoverAccessibility` menekan **Allow** pada dialog di layar mati | a11y `findAccessibilityNodeInfosByText("Allow")` | S |

## Terverifikasi di HP ini (dicek ulang 2026-11-08)

```sh
adb shell settings get system sub_screen_brightness   # → 101  (kunci brightness cover Samsung ada; nilainya berubah-ubah)
adb shell settings get system screen_brightness       # → 51   (kunci normal, bukan yang dipakai cover)
adb shell cmd window -h | grep -A1 rotation           # → user-rotation [-d DISPLAY_ID] [free|lock] [rotation]
                                                      #   fixed-to-user-rotation [-d DISPLAY_ID] [enabled|...]
adb shell cmd window density -d 1                     # → "Physical density: 320" (override per display didukung)
adb shell settings get secure multistar_setting_repository   # → slot 26 berisi "pkg,userId;…", 35 entri
adb shell dumpsys package dev.opencover | grep WRITE_SECURE_SETTINGS   # → granted=true
```

Jadi ketiga API yang dipakai OpenCover (brightness cover, rotasi per display, density per display) plus jalur
allowlist MultiStar tersedia di One UI 9 / Android 17 ini. Satu yang tidak: `cmd phone
get-allowed-network-types-for-users -s 0` → `No valid subscription found`, jadi toggle 5G butuh SIM aktif.

## Enam koreksi setelah baca source

1. **Brightness bukan `SCREEN_BRIGHTNESS`.** OpenCover memakai kunci Samsung `system/sub_screen_brightness`
   (1..255) lewat Shizuku — tidak perlu appop `WRITE_SETTINGS` untuk ini.
2. **Rotasi per app** memakai `cmd window user-rotation -d 1 lock N` + `fixed-to-user-rotation`, bukan
   `wm` atau `settings put system user_rotation` global.
3. **Overlay 1×1 bukan sekadar keep-awake.** Sejak Android 15, start activity dari background hanya boleh
   kalau proses punya window overlay yang terlihat (kalau tidak: `BAL_BLOCK`). Launcher kita mengandalkan
   appop `SYSTEM_ALERT_WINDOW` + accessibility service; kalau back-to-launcher gagal tanpa sebab jelas,
   ini tersangka pertamanya dan solusinya overlay kecil di display 1.
4. **Keeper state lipat.** `phone/Fold.kt` (opsi *keep cover on*) memasang watcher shell lewat Shizuku
   (`nohup`, tetap hidup tanpa proses OpenCover) yang membaca **logcat** `DeviceStateManagerService` dan
   meminta ulang state **1 (TENT)** setiap kali sistem "Committing state" 0 atau 3 — Android membuang override
   setiap kali state fisik berubah. TENT dipilih karena sensor HP tidak pernah melaporkannya, jadi cover tetap
   menyala apa pun kata sensor engsel. Ini berbeda dari `/data/local/tmp/keepcover.sh` di HP ini, yang
   *polling* `cmd device_state print-state` tiap 1 s dan memaksa state 0. **Sudah ditangani di launcher kita
   (2026-11-08):** terukur bahwa override `CLOSED` bertahan saat dibuka tetapi hilang saat dilipat kembali;
   `keepFolded()` menjalankan penjaga shell sendiri yang memasangnya lagi pada setiap `onStateChanged`
   (uji di HP: buka → lipat → buka, cover tetap menyala). Lihat [HOW-IT-WORKS](HOW-IT-WORKS.md) §1.
   Catatan dari HP ini (2026-11-08): ada 11 proses `opencover-fold-watch` yang jalan bersamaan.

5. **Ada jalan menghindari shell sama sekali.** `apps/CoverAllowList.kt` menulis semua paket ke secure
   setting `multistar_setting_repository` (slot 26, `"pkg,userId;…"`) — daftar yang dibaca MultiStar.
   Setelah itu `startActivity` (dari accessibility service atau activity OpenCover) **diizinkan** Samsung di
   cover dan bisa membawa animasi custom — kartu yang tumbuh dari ikon (animasi Compose OpenCover sendiri,
   karena cover Samsung mengabaikan animasi grow sistem) lalu app fade-in di atasnya; `am start` hanya fallback. Launcher kita melewati jalur ini dan bergantung pada shell
   di setiap launch, yang berarti bergantung pada Shizuku hidup.

6. **Prosesnya bukan cuma accessibility.** `watcher/CoverWatcher` adalah foreground service `specialUse`
   yang menangani display change, `USER_PRESENT`, heat guard, dan overlay; `BootReceiver` menjalankan
   ulang setelah reboot **dan** setelah update APK. Launcher kita hanya punya a11y service, jadi tidak ada
   yang menyala lagi setelah `pm install -r` selain lewat a11y yang mungkin sudah dimatikan.

## Yang sudah kita punya dan mereka tidak

| Kita | OpenCover |
|---|---|
| Tekan **Start** Shizuku otomatis ±90 s setelah boot (One UI 9 mematahkan start-on-boot-nya) | Start manual setiap reboot |
| Wallpaper halftone dot-matrix di GPU + gaya Nothing | foto/GIF, tanpa halftone |
| `pullToCover()` untuk app yang nyangkut di task layar mati | `KeepOnCover` (mirip, per window) |
| Paksa `device_state 0` setiap binder Shizuku datang | opsi "keep cover on" (tahan TENT + watcher logcat), tidak selalu |

## Urutan pengerjaan

1. ~~**Aksi notifikasi**~~ — **selesai**: maks 3 tombol, Balas membuka app, notifikasi panggilan tanpa tombol
   (Answer dari listener tidak pernah membuat panggilan WA aktif di HP ini).
2. ~~**Media player**~~ **selesai (minimal)**: baris di panel, sesi teratas, ⏮ ⏯ ⏭; `MediaSessionManager` menumpang
   izin listener. Tanpa pilihan sesi, seek, sampul.
3. **Allowlist cover** — tulis `multistar_setting_repository` (slot 26); setelah ini membuka app tidak lagi
   bergantung pada Shizuku. ~~Approve dialog debugging~~ **selesai dengan cara lain**: dialog asli dipindah ke cover
   dan dijawab pengguna (bukan a11y menekan Allow); belum diuji dengan dialog asli.
4. **Tile lengkap + reorder** — **sebagian selesai**: NFC, dark mode, keep awake; panel ringkas 5 tile, usap bawah =
   semua. Hotspot ditunda (tanpa SIM). Belum: rotate, mirror, urutan di `SettingsStore`. ~~Slider brightness~~ **selesai**:
   `sub_screen_brightness` + mode otomatis (terbukti di HP: mode 0 + nilai 20 meredupkan cover).
5. **Density/font/rotasi** — `wm density -d 1`, `cmd window user-rotation -d 1 lock N`, disimpan per paket.
6. **Keep-awake overlay + foreground service + boot receiver** — menutup risiko BAL dan membuat app menyala
   lagi setelah reboot maupun update APK.
7. **Edge lighting / banking step-aside** — kecil, kerjakan kalau salah satunya mengganggu.
8. **Balas teks** — baru setelah ada keyboard yang bisa hidup di cover (±700 LOC IME).
9. **Desktop di monitor** — opsional, hanya kalau ada monitor USB-C; 1.451 LOC di OpenCover.

Tiga yang pertama hanya menyentuh `NotificationStore`, `NotificationPanel`, `SettingsStore`, `CoverOpener`:
tanpa perubahan arsitektur (kecuali baris izin `WRITE_SECURE_SETTINGS` dan satu broadcast receiver di langkah 6).

## Yang sengaja tidak diambil

Mirror layar dalam dan resize layar dalam (tidak ada gunanya tanpa panel dalam); AOD/lock screen sendiri
(Samsung sudah punya, overlay di atas keyguard menambah jalur rawan); widget home, icon pack, wallpaper
animasi (kosmetik, bertabrakan dengan gaya monokrom); nav bar sendiri (nav bar Samsung sudah jalan).

Desktop di monitor USB-C **tidak** masuk daftar buang: panel dalam tidak dipakai, dan dengan monitor
terpasang HP ini bisa jadi desktop. Mahal (1.451 LOC, butuh virtual display lewat Shizuku), jadi taruh di
akhir, tetapi jangan dicoret.

## Catatan pengujian

OpenCover menaruh logika rawan di fungsi murni + unit test (39 file: layout, parsing tile, rotasi, warna
edge, AOD). Kalau fitur di atas dikerjakan, ikuti pola yang sama: parser dan aturan dapat test JVM,
sisanya diverifikasi manual di cover.
