# NEXUS-CLI v2.0

Launcher Android 11 (API 30) dengan antarmuka terminal-first dan dashboard bergaya bento-grid. Dibangun menggunakan Kotlin, XML, dan Gradle Kotlin DSL.

## Tentang Proyek Ini

NEXUS-CLI dirancang khusus untuk orientasi landscape (mendatar). Aplikasi ini menggabungkan estetika dashboard modular modern dengan antarmuka berbasis teks (Command Line Interface). Tujuannya adalah menciptakan pengalaman penggunaan ponsel yang bebas dari gangguan visual, ramah baterai, serta mengoptimalkan kenyamanan navigasi berbasis papan ketik.

Target OS: Android 11 (API Level 30).
Orientasi: Terkunci penuh pada mode landscape.
Gaya Visual: True dark mode (#0A0A0C), aksen hijau neon (#00FF66), dan kartu bergaya Glassmorphism (semi-transparan).

## Struktur Halaman (Multi-Page ViewPager2)

Launcher ini menggunakan ViewPager2 untuk navigasi usap horizontal di antara 4 halaman utama:

1. Halaman 1: Widget Musik & Cuaca
   - Kotak kiri: Pemutar musik dengan sampul album, judul lagu, progress bar, dan tombol kontrol.
   - Kotak kanan: Jam digital besar, tanggal, dan prakiraan cuaca 7 hari.

2. Halaman 2: Quick Launch Grid
   - Grid 4x3 berisi tombol pintas (Terminal, VS Code, Browser, Files, Spotify, Discord, dll).
   - Indikator halaman di bagian bawah.

3. Halaman 3: Dashboard Sesi & GitHub
   - Panel kiri: Daftar sesi kerja dengan indikator status warna.
   - Panel kanan: Notifikasi pull request GitHub.

4. Halaman 4: Dashboard Sistem & Hardware
   - Jam, cuaca, dan pemutar musik ringkas di bagian atas.
   - Empat kotak grafik pemantauan (CPU, GPU, RAM, Network) dengan SparklineView.

## Fitur Utama

- Shell Engine & Terminal: Mendukung perintah help, clear, time, date, apps, open [app], alias, cd, ls, rm, neofetch, weather, dan exit-Nexus.
- Kiosk Mode: Menggunakan LockTaskMode untuk mengunci layar. Navigation bar disembunyikan. Perintah exit-Nexus digunakan untuk keluar.
- Media Player: NotificationListenerService untuk membaca judul lagu dan status Play/Pause dari aplikasi musik.
- Live Monitors: Pemantauan baterai, jaringan, CPU, dan RAM secara real-time.
- Weather API: Integrasi dengan Open-Meteo untuk data cuaca aktual.

## Daftar Perintah (Command Reference)

- help: Menampilkan daftar seluruh perintah.
- apps: Menampilkan daftar aplikasi yang terinstal.
- open [nama_app]: Membuka aplikasi target.
- claude [pesan]: Simulasi jembatan AI.
- time / date: Menampilkan waktu dan tanggal sistem.
- clear: Membersihkan layar terminal.
- exit-Nexus: Keluar dari mode Kiosk dan menutup aplikasi.

## Kelebihan

- Bebas distraksi visual karena tidak ada deretan ikon aplikasi.
- Navigasi berbasis teks yang cepat dan efisien.
- Estetika cyberpunk dengan desain kaca (Glassmorphism).
- Efisien baterai dengan true dark mode.

## Kekurangan

- Membutuhkan penyesuaian untuk menghafal perintah teks.
- Terbatas pada orientasi landscape.
- Fitur Kiosk Mode mungkin dibatasi oleh kebijakan sistem operasi perangkat tertentu.

## Persyaratan Sistem

- Android 11 (API Level 30) atau lebih baru.
- Android Studio versi terbaru (mendukung Kotlin DSL dan Java 21).
- JDK 21.

## Cara Build dan Install

1. Buka proyek di Android Studio.
2. Lakukan Gradle Sync.
3. Hubungkan perangkat Android dengan USB Debugging aktif.
4. Klik Run (atau Build APK untuk instalasi manual).

## Keyboard Recommendation

Untuk pengalaman mengetik terminal terbaik, disarankan menggunakan keyboard pihak ketiga bertema gelap seperti Gboard Dark Mode, Microsoft SwiftKey Dark Theme, atau Termux keyboard.

## Roadmap v2.1.0

- Integrasi API Claude yang sebenarnya.
- Integrasi MediaSessionManager untuk kontrol Spotify penuh.
- Widget cuaca dengan animasi dinamis.
- Fitur Text-to-Speech pada terminal.

## Lisensi

Proyek ini dibuat untuk keperluan eksperimen dan pengembangan pribadi.
