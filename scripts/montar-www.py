#!/usr/bin/env python3
"""Monta www/index.html (o que vai dentro do app) a partir de src/app.html."""
from pathlib import Path

raiz = Path(__file__).resolve().parent.parent
corpo = (raiz / "src" / "app.html").read_text(encoding="utf-8")
cabeca = """<!doctype html>
<html lang="pt-BR">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover, user-scalable=no">
<meta name="theme-color" content="#0F1720">
<style>
:root{padding-top:env(safe-area-inset-top,0px);padding-bottom:env(safe-area-inset-bottom,0px)}
body{margin:0;font-size:14px;-webkit-tap-highlight-color:transparent}
img{max-width:100%}
[hidden]{display:none!important}
</style>
</head>
<body>
"""
(raiz / "www").mkdir(exist_ok=True)
(raiz / "www" / "index.html").write_text(cabeca + corpo + "\n</body>\n</html>\n", encoding="utf-8")
print("www/index.html montado")
