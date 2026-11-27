package br.rafael.figurinhas

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

// descreve figurinha nova sem mandar a imagem pra lugar nenhum:
// le o texto escrito nela e reconhece o que aparece (gato, cachorro, comida...)
object Descrever {
    private val texto by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val rotulos by lazy { ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(.55f).build()) }

    // roda fora da thread principal
    fun descrever(bmp: Bitmap): Pair<String, String> {
        val img = InputImage.fromBitmap(bmp, 0)
        val lido = try {
            Tasks.await(texto.process(img)).text.replace('\n', ' ').replace(Regex("\\s+"), " ").trim().lowercase()
        } catch (e: Exception) { "" }
        val tags = try {
            Tasks.await(rotulos.process(img)).mapNotNull { PT[it.text.lowercase()] }.distinct().joinToString(" ")
        } catch (e: Exception) { "" }
        return lido.take(120) to tags
    }

    // rotulos do ml kit vem em ingles; os que importam pra figurinha viram portugues (com sinonimos)
    private val PT = mapOf(
        "cat" to "gato gatinho", "dog" to "cachorro cao", "bird" to "passaro", "fish" to "peixe", "horse" to "cavalo",
        "bear" to "urso", "teddy bear" to "urso de pelucia", "monkey" to "macaco", "rabbit" to "coelho", "duck" to "pato",
        "chicken" to "galinha", "cattle" to "boi vaca", "sheep" to "ovelha", "pig" to "porco", "frog" to "sapo",
        "insect" to "inseto", "butterfly" to "borboleta", "turtle" to "tartaruga", "penguin" to "pinguim", "squirrel" to "esquilo",
        "lizard" to "lagarto", "reptile" to "lagarto", "dinosaur" to "dinossauro", "pet" to "bicho animal", "toy" to "brinquedo",
        "smile" to "sorrindo feliz", "laugh" to "rindo risada", "selfie" to "selfie pessoa", "beard" to "homem barba",
        "baby" to "bebe", "child" to "crianca", "crowd" to "gente multidao", "team" to "time grupo", "hand" to "mao",
        "eyelash" to "olho", "hair" to "cabelo", "glasses" to "oculos", "sunglasses" to "oculos escuros", "hat" to "chapeu",
        "cap" to "bone", "jacket" to "jaqueta", "suit" to "terno", "tie" to "gravata", "shoe" to "sapato", "jersey" to "camisa futebol",
        "food" to "comida", "cake" to "bolo aniversario", "bread" to "pao", "pizza" to "pizza", "fast food" to "lanche comida",
        "cookie" to "biscoito", "fruit" to "fruta", "vegetable" to "legume", "dessert" to "doce sobremesa", "drink" to "bebida",
        "wine" to "vinho bebida", "cup" to "xicara cafe", "coffee" to "cafe", "bottle" to "garrafa bebida", "tableware" to "prato comida",
        "car" to "carro", "bicycle" to "bicicleta", "motorcycle" to "moto", "bus" to "onibus", "train" to "trem", "airplane" to "aviao",
        "boat" to "barco", "flower" to "flor", "plant" to "planta", "tree" to "arvore", "sky" to "ceu", "sunset" to "por do sol",
        "beach" to "praia", "snow" to "neve frio", "fire" to "fogo", "rain" to "chuva", "night" to "noite",
        "party" to "festa", "dance" to "dancando danca", "fun" to "divertido", "musical instrument" to "musica instrumento",
        "guitar" to "violao guitarra", "musical" to "musica", "television" to "televisao", "mobile phone" to "celular",
        "computer" to "computador", "money" to "dinheiro", "flag" to "bandeira", "christmas" to "natal", "balloon" to "balao festa",
        "superhero" to "super heroi", "cartoon" to "desenho", "comics" to "desenho", "poster" to "texto cartaz",
        "bed" to "cama dormindo", "couch" to "sofa", "sleep" to "dormindo sono", "sitting" to "sentado", "running" to "correndo",
        "skateboard" to "skate", "sports" to "esporte", "soccer" to "futebol", "muscle" to "musculoso academia", "tattoo" to "tatuagem",
        "skull" to "caveira esqueleto", "skeleton" to "esqueleto", "heart" to "coracao amor", "love" to "amor", "star" to "estrela"
    )
}
