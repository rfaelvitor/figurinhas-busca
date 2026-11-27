#!/bin/sh
# roda no emulador do CI: instala, carrega dados de teste e tira print de cada tela
set -x
P=br.rafael.figurinhas
A="$P/.MainActivity"
mkdir -p telas
adb install -r figurinhas.apk
adb shell run-as $P mkdir -p files
adb push demo.zip /data/local/tmp/demo.zip
adb shell run-as $P cp /data/local/tmp/demo.zip files/demo.zip
adb shell settings put global window_animation_scale 0
foto() { sleep "$2"; adb exec-out screencap -p > "telas/$1.png"; }
abrir() { adb shell am start -S -W -n $A "$@"; }

abrir --es demo_importar demo.zip --es demo_tela configurar --ei demo_passo 0; foto 01_ola 4
for p in 1 2 3 4; do abrir --es demo_tela configurar --ei demo_passo $p; foto 0$((p+1))_passo$p 3; done
abrir --es demo_tela busca --es demo_recentes "bom dia,pix,gato"; foto 06_busca_inicio 4
abrir --es demo_tela busca --es demo_q "gato comendo"; foto 07_busca_gato_comendo 4
abrir --es demo_tela busca --es demo_q "to cansado"; foto 08_busca_cansado 4
abrir --es demo_tela busca --es demo_q "xyzqw"; foto 09_sem_resultado 4
abrir --es demo_tela busca --ez demo_ajustes true; foto 10_ajustes 4
adb shell cmd uimode night yes
abrir --es demo_tela configurar --ei demo_passo 0; foto 11_ola_escuro 4
abrir --es demo_tela busca --es demo_q "dinheiro"; foto 12_busca_escuro 4
adb shell cmd uimode night no
abrir --es demo_tela sugerir --es demo_texto "nossa que dia incrivel no trabalho hoje, adorei ficar 3 horas em reuniao"; foto 13_sugerir 5
adb shell run-as br.rafael.figurinhas cat files/diag.log > telas/diag.txt || true
adb logcat -d -s AndroidRuntime:E > telas/erros.txt || true
