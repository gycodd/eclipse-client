<div align="center">

# Eclipse для Android

**Android-форк VPN-клиента [Eclipse](https://github.com/encore9999/eclipse-client) на базе Xray-core и sing-box**

</div>

Порт iOS-приложения на Kotlin + Jetpack Compose. Логика та же: подписки v2ray/Xray,
VLESS (XTLS-Vision, REALITY), VMess, Trojan, Shadowsocks, Hysteria2, TUIC v5,
маршрутизация, автопереключение серверов, русский интерфейс.

> **Статус: первая версия, ни разу не собиралась и не запускалась.** Код написан без Android SDK и
> без доступа к сети, поэтому проверена только та часть, что компилируется на чистой JVM
> (парсер ссылок, построители конфигов, пинг). Первая сборка в CI почти наверняка потребует
> мелких правок — см. раздел «Что проверить первым».

## Как это устроено

| iOS | Android |
|---|---|
| `NEPacketTunnelProvider` | `VpnService` (`vpn/EclipseVpnService.kt`) |
| `Tun2SocksKit` | `tun2socks` (gVisor), встроен в Go-библиотеку (`xray/xraybridge/tun.go`) |
| `Xray.xcframework` (gomobile) | `xray.aar` (gomobile, `-target=android`) |
| `UserDefaults` + Keychain | `SharedPreferences` + JSON-файл в приватной папке приложения |
| `NWPathMonitor` | `ConnectivityManager.NetworkCallback` |
| On-Demand / kill-switch | системные «Постоянный VPN» и «Блокировать соединения без VPN» |
| SwiftUI | Jetpack Compose + Material 3 |

Схема трафика: `приложения → TUN (VpnService) → tun2socks → локальный SOCKS5 с паролем → Xray / sing-box → сервер`.
Само приложение (а значит и ядро) исключено из VPN через `addDisallowedApplication`,
поэтому соединения ядра к серверу идут мимо туннеля и петли не возникает.

Что перенесено: парсер ссылок и подписок, конфиги Xray и sing-box (TUIC/Hysteria2), заголовки подписок
(трафик, срок, announce, HWID), пинг (TCP / HTTP GET / HEAD / ICMP), автообновление, прямые правила,
GeoIP/GeoSite, DoH, Mux, фрагментация TLS, автопереключение по проверке трафика,
перезапуск ядра при смене сети, журнал с вырезанием секретов, QR-сканер, плитка быстрых настроек.

Чего нет по сравнению с iOS: редактора конфига, показа QR-кода сервера, импорта QR из картинки,
анимированного фона. geoip/geosite не работают для Hysteria2/TUIC (sing-box нужны rule-set'ы).

## Сборка

### Через GitHub Actions (рекомендуется)

Загрузите проект в репозиторий — `.github/workflows/android.yml` соберёт `xray.aar` и APK.
Готовые файлы появятся в артефактах запуска (`Eclipse-android-apk`): универсальный APK и по ABI.
Без своего ключа APK подписывается debug-ключом — его можно ставить сразу.
Для своей подписи задайте переменные `ECLIPSE_KEYSTORE`, `ECLIPSE_KEYSTORE_PASSWORD`,
`ECLIPSE_KEY_ALIAS`, `ECLIPSE_KEY_PASSWORD`.

### Локально

Нужны Go, JDK 17, Android SDK 34 и NDK 26.

```bash
cd android/xray
go mod init example.com/xrayroot
go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest
export PATH=$PATH:$(go env GOPATH)/bin
gomobile init
go get github.com/xtls/xray-core@latest github.com/sagernet/sing-box@v1.14.2 github.com/xjasonlyu/tun2socks/v2@latest
go get -tool golang.org/x/mobile/cmd/gobind golang.org/x/mobile/cmd/gomobile
go mod tidy
gomobile bind -target=android/arm64,android/arm,android/amd64 -androidapi 24 \
  -javapkg app.eclipse -trimpath -tags="with_quic" -ldflags="-s -w -checklinkname=0" \
  -o ../app/libs/xray.aar ./xraybridge
cd .. && ./gradlew assembleRelease
```

## Что проверить первым

1. **Go-зависимости (`go mod tidy`).** Xray-core, sing-box и tun2socks тянут разные версии gVisor —
   возможен конфликт. Если так, закрепите версии в `go.mod`.
2. **API tun2socks.** `tun.go` использует `engine.Insert(&engine.Key{...})`, `engine.Start()`, `engine.Stop()`.
   В некоторых версиях `Start()` при ошибке вызывает `log.Fatalf` (процесс завершается) — при первом запуске
   смотрите журнал; при необходимости закрепите версию tun2socks, где сигнатуры совпадают.
3. **Имя Java-класса.** Код ожидает `app.eclipse.xraybridge.Xraybridge` (из `-javapkg app.eclipse`).
4. **Android Studio / Gradle.** Версии плагинов (AGP 8.5.2, Kotlin 2.0.20, Compose BOM 2024.09.00) подобраны вручную.

## Использование

1. Вкладка **Серверы** → **Добавить подписку** (ссылка, буфер обмена или QR).
2. Выберите сервер, на вкладке **Главная** нажмите кнопку питания и разрешите VPN.
3. Для постоянного VPN и блокировки без VPN: **Настройки → Постоянный VPN** (системные настройки Android).

## Лицензия

GPL-3.0, как и у оригинального проекта. Xray-core — MPL-2.0, sing-box — GPL-3.0+, tun2socks — GPL-3.0.
