package capsule

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

enum class SlideType { HTML, MANIM }

data class SlideSegment(
    val index: Int,
    val title: String,
    val speakerNote: String,
    val type: SlideType = SlideType.HTML,
    val manimScene: String? = null   // nom de la classe Python Scene si type == MANIM
)

data class CapsuleScript(
    val deckName: String,
    val slides: List<SlideSegment>
)

open class CapsuleExtension @Inject constructor(objects: ObjectFactory) {
    val ttsEngine: Property<String> = objects.property(String::class.java)
        .convention("piper")

    val ttsVoice: Property<String> = objects.property(String::class.java)
        .convention("fr_FR-siwis-medium")

    val piperExecutablePath: Property<String> = objects.property(String::class.java)
        .convention("piper")

    val ttsFallbackEnabled: Property<Boolean> = objects.property(Boolean::class.java)
        .convention(true)

    val outputDir: Property<String> = objects.property(String::class.java)
        .convention("capsule")

    val sliderScriptDir: Property<String> = objects.property(String::class.java)
        .convention("capsule")

    val viewportWidth: Property<Int> = objects.property(Int::class.java)
        .convention(1408)

    val viewportHeight: Property<Int> = objects.property(Int::class.java)
        .convention(792)

    val playwrightTimeout: Property<Double> = objects.property(Double::class.java)
        .convention(120_000.0)

    val chromiumExecutablePath: Property<String> = objects.property(String::class.java)
        .convention("")

    val deckSourceDir: Property<String> = objects.property(String::class.java)
        .convention("docs/asciidocRevealJs")

    val ffmpegExecutablePath: Property<String> = objects.property(String::class.java)
        .convention("ffmpeg")

    val distribOutputWidth: Property<Int> = objects.property(Int::class.java)
        .convention(1080)

    val distribOutputHeight: Property<Int> = objects.property(Int::class.java)
        .convention(1920)

    val compositeContextOutputFile: Property<String> = objects.property(String::class.java)
        .convention("capsule/capsule-context.json")

    val slideDurationSeconds: Property<Double> = objects.property(Double::class.java)
        .convention(5.0)

    val espeakVoice: Property<String> = objects.property(String::class.java)
        .convention("fr")

    val espeakSpeed: Property<Int> = objects.property(Int::class.java)
        .convention(150)

    val manimExecutablePath: Property<String> = objects.property(String::class.java)
        .convention("manim")

    val manimQuality: Property<String> = objects.property(String::class.java)
        .convention("l")   // l=480p, m=720p, h=1080p, k=4K

    val manimScriptsDir: Property<String> = objects.property(String::class.java)
        .convention("src/manim")
}
