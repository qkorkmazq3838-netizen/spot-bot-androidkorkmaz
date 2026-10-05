# Spot Bot Android v0.1

Telefon için native Android uygulaması.

## Ne yapar?
- Binance Spot PUBLIC verisini kullanır
- API key istemez
- Gerçek alım/satım emri göndermez
- USDT paritelerini hacme göre tarar
- 4H / 1H / 15M verisini analiz eder
- EMA20/50/200, RSI14, MACD
- Basit piyasa rejimi
- Basit HH/HL filtresi
- 0-100 skor
- VALID / WATCH / SKIP sonucu

## APK üretme
Bu repo GitHub'a yüklendiğinde `.github/workflows/build-apk.yml`
otomatik olarak debug APK üretir.

GitHub > Actions > Build Android APK > Artifacts > SpotBot-v0.1-APK
