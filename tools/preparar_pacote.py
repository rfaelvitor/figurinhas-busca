# converte as favoritas pro formato que o whatsapp aceita em pacote e gera dados.zip pro app
# estatica: 512x512 webp ate 100KB / animada: 512x512 webp ate 500KB
import io, json, os, sys, zipfile
from PIL import Image, ImageSequence

src, indice, saida = sys.argv[1], sys.argv[2], sys.argv[3]
itens = json.load(open(indice, encoding="utf-8"))["itens"]

def quadrado(im):
    im = im.convert("RGBA")
    im.thumbnail((512, 512), Image.LANCZOS)
    tela = Image.new("RGBA", (512, 512), (0, 0, 0, 0))
    tela.paste(im, ((512 - im.width) // 2, (512 - im.height) // 2))
    return tela

def estatica(im):
    q = quadrado(im)
    for qual in (90, 80, 70, 60, 50, 40, 30):
        b = io.BytesIO(); q.save(b, "WEBP", quality=qual, method=6)
        if b.tell() <= 100_000:
            return b.getvalue()
    return b.getvalue()

def animada(im):
    frames, durs = [], []
    for f in ImageSequence.Iterator(im):
        frames.append(quadrado(f)); durs.append(max(int(f.info.get("duration", 80)), 8))
    # whatsapp aceita no maximo 10s
    total, i = 0, 0
    while i < len(frames) and total + durs[i] <= 10_000:
        total += durs[i]; i += 1
    frames, durs = frames[:max(i, 1)], durs[:max(i, 1)]
    escala, passo = 512, 1
    for tentativa in range(12):
        fs = frames[::passo]; ds = [sum(durs[k:k + passo]) for k in range(0, len(durs), passo)]
        if escala < 512:
            fs = [quadrado(f.resize((escala, escala), Image.LANCZOS)) for f in fs]
        qual = max(20, 75 - tentativa * 8)
        b = io.BytesIO()
        fs[0].save(b, "WEBP", save_all=True, append_images=fs[1:], duration=ds, loop=0, quality=qual, method=4)
        if b.tell() <= 500_000:
            return b.getvalue()
        if tentativa >= 4:
            passo += 1
        if tentativa >= 8:
            escala = 384
    return b.getvalue()

z = zipfile.ZipFile(saida, "w", zipfile.ZIP_STORED)
saida_itens, grandes = [], 0
for it in itens:
    if not it.get("carregou", True):
        continue
    im = Image.open(os.path.join(src, it["arquivo"]))
    anim = getattr(im, "n_frames", 1) > 1
    dados = animada(im) if anim else estatica(im)
    if len(dados) > (500_000 if anim else 100_000):
        grandes += 1
    nome = f"{it['n']:03d}_{'a' if anim else 's'}.webp"
    z.writestr("stickers/" + nome, dados)
    saida_itens.append({"n": it["n"], "arquivo": nome, "animada": anim, "texto": it["texto"], "tags": it["tags"]})
z.writestr("indice.json", json.dumps({"versao": 1, "itens": saida_itens}, ensure_ascii=False))
z.close()
print(len(saida_itens), "figurinhas,", sum(i["animada"] for i in saida_itens), "animadas,", grandes, "acima do limite")
