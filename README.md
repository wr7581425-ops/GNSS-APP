# Coletora GNSS

App Android de coletora para levantamento e locação RTK: conecta no receptor (ComNav S6 e outros) pelo Wi-Fi, lê o NMEA, recebe correção NTRIP da RBMC e repassa o NMEA por TCP para outros equipamentos (ecobatímetro, notebook).

## Como instalar no celular
1. Abra a aba **Releases** deste repositório (ou **Actions**, no último build verde).
2. Baixe o arquivo `ColetoraGNSS-1.0.X.apk` pelo celular Android.
3. Abra o arquivo e autorize "instalar apps desta fonte".

Cada envio para a branch `main` gera um APK novo automaticamente (GitHub Actions).

## Estrutura
- `src/app.html`: interface e lógica do app (é o que se edita).
- `www/index.html`: versão montada que vai dentro do app (`python3 scripts/montar-www.py`).
- `native/`: código Android (conexão TCP com o receptor, cliente NTRIP, servidor NMEA).
- `scripts/patch-android.sh`: ajusta o projeto Android gerado pelo Capacitor.
- `.github/workflows/android.yml`: monta o APK na nuvem.

## Primeira conexão com o S6
1. Ligue o Wi-Fi do receptor e conecte o celular nessa rede.
2. No app: Dispositivo › Conexão › Wi-Fi, IP `192.168.1.1` e a porta da saída NMEA do receptor.
3. Toque em Conectar. O status deve mudar para Autônomo e, com NTRIP ligado, para FIX.
