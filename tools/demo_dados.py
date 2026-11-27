# figurinhas falsas pra testar as telas no emulador (sem nenhuma figurinha real)
import io, json, sys, zipfile
from PIL import Image, ImageDraw, ImageFont

itens = [
    ("gato", "#F4A261", "", "gato laranja comendo pizza fome"),
    ("cachorro", "#8D6E63", "BOM DIA", "cachorro sorrindo bom dia"),
    ("pix", "#2A9D8F", "AGUARDANDO O PIX", "texto aguardando pix dinheiro"),
    ("sono", "#6D597A", "SEGUNDOU", "pessoa dormindo cansado segunda feira trabalho"),
    ("rindo", "#E9C46A", "KKKKKK", "emoji rindo risada"),
    ("triste", "#457B9D", "", "gato triste chorando"),
    ("bravo", "#E63946", "", "cachorro bravo raiva"),
    ("danca", "#B5838D", "", "macaco dancando festa"),
    ("nemligo", "#9C89B8", "NEM LIGO", "pato indiferente nem ligo"),
    ("esqueleto", "#264653", "", "esqueleto dancando"),
    ("cafe", "#7F5539", "", "gato tomando cafe manha"),
    ("amor", "#F28482", "", "coracao amor carinho"),
]
try:
    fonte = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 54)
except Exception:
    fonte = ImageFont.load_default()

z = zipfile.ZipFile(sys.argv[1], "w")
idx = []
for n, (nome, cor, texto, tags) in enumerate(itens, 1):
    anim = n % 4 == 0
    def quadro(k):
        im = Image.new("RGBA", (512, 512), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
        d.rounded_rectangle([40, 40 + k * 12, 472, 472 + k * 12], 90, fill=cor)
        d.ellipse([170, 170, 215, 215], fill="white"); d.ellipse([297, 170, 342, 215], fill="white")
        d.arc([170, 230, 342, 340], 20, 160, fill="white", width=14)
        if texto:
            d.multiline_text((256, 410), texto.replace(" O ", "\nO "), font=fonte, fill="white", anchor="mm", align="center")
        return im
    b = io.BytesIO()
    if anim:
        quadro(0).save(b, "WEBP", save_all=True, append_images=[quadro(1), quadro(2), quadro(1)], duration=150, loop=0)
    else:
        quadro(0).save(b, "WEBP", quality=80)
    arq = f"{n:03d}_{'a' if anim else 's'}.webp"
    z.writestr("stickers/" + arq, b.getvalue())
    idx.append({"n": n, "arquivo": arq, "animada": anim, "texto": texto.lower(), "tags": tags})
z.writestr("indice.json", json.dumps({"versao": 1, "itens": idx}, ensure_ascii=False))
z.close()
print(len(idx), "figurinhas de teste")
