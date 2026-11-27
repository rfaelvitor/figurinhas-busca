# gera vetores de palavras em portugues a partir de um modelo de frases multilingue
# a busca do app soma esses vetores pra entender o sentido (gato comendo ~ gato segurando pao)
import json, re, struct, sys, unicodedata, urllib.request
import numpy as np
from sentence_transformers import SentenceTransformer

FREQ = "https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/pt_br/pt_br_50k.txt"
DIM = 160
saida = sys.argv[1]

def norm(s):
    s = unicodedata.normalize("NFD", s.lower())
    return "".join(c for c in s if unicodedata.category(c) != "Mn")

originais = {}
def add(p):
    p = p.strip().lower()
    if len(p) < 2 or not re.fullmatch(r"[a-záàâãéêíóôõúüç]+", p):
        return
    originais.setdefault(norm(p), p)

for linha in urllib.request.urlopen(FREQ).read().decode("utf-8").splitlines()[:40000]:
    add(linha.split(" ")[0])
for g in json.load(open("app/src/main/assets/sinonimos.json", encoding="utf-8")):
    for p in g:
        for t in p.split():
            add(t)
for p in open("tools/vocab_extra.txt", encoding="utf-8").read().split():
    add(p)

chaves = list(originais)
print(len(chaves), "palavras")
m = SentenceTransformer("sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2")
emb = m.encode([originais[k] for k in chaves], batch_size=512, normalize_embeddings=True, show_progress_bar=False)
media = emb.mean(0)
x = emb - media
_, _, vt = np.linalg.svd(x[:20000], full_matrices=False)
y = x @ vt[:DIM].T
escala = np.abs(y).max(1) / 127.0
escala[escala == 0] = 1
q = np.round(y / escala[:, None]).astype(np.int8)

with open(saida, "wb") as f:
    f.write(b"VET1"); f.write(struct.pack(">ii", len(chaves), DIM))
    for k in chaves:
        b = k.encode("utf-8"); f.write(struct.pack(">H", len(b))); f.write(b)
    f.write(escala.astype(">f4").tobytes())
    f.write(q.tobytes())
print("ok", saida)
