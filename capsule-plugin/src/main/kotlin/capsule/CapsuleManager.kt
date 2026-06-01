package capsule

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.gradle.api.tasks.Internal
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

class CapsuleManager(private val project: Project) {

    private val capsuleExt = project.extensions.getByType(CapsuleExtension::class.java)

    fun registerTasks() {
        project.registerGenerateCapsuleScriptTask()
        project.registerGenerateCapsuleTask()
        project.registerGenerateCapsuleVideoTask()
        project.registerDeployCapsuleTask()
        project.registerCollectCapsuleContextTask()
        project.registerTransformCapsuleContextTask()
    }

    private fun Project.registerGenerateCapsuleScriptTask() {
        tasks.register("generateCapsuleScript", CapsuleScriptTask::class.java) { task ->
            task.group = "generate"
            task.description = "Reads *-script.txt produced by slider-gradle and validates the capsule script"
            task.capsuleExtension = this@CapsuleManager.capsuleExt
        }
    }

    private fun Project.registerGenerateCapsuleTask() {
        tasks.register("generateCapsule", CapsuleBuildTask::class.java) { task ->
            task.group = "generate"
            task.description = "Generates TTS audio files from capsule scripts (Piper placeholder)"
            task.dependsOn("generateCapsuleScript")
            task.capsuleExtension = this@CapsuleManager.capsuleExt
        }
    }

    private fun Project.registerGenerateCapsuleVideoTask() {
        tasks.register("generateCapsuleVideo", CapsuleVideoTask::class.java) { task ->
            task.group = "generate"
            task.description = "Injects TTS audio into deck HTML then captures video via Playwright Java"
            task.dependsOn("generateCapsule")
            task.capsuleExtension = this@CapsuleManager.capsuleExt
        }
    }

    private fun Project.registerDeployCapsuleTask() {
        tasks.register("deployCapsule", CapsuleDistribTask::class.java) { task ->
            task.group = "deploy"
            task.description = "Recadre les capsules en format vertical 9:16 (TikTok/Shorts) via FFmpeg"
            task.dependsOn("generateCapsuleVideo")
            task.capsuleExtension = this@CapsuleManager.capsuleExt
        }
    }

    private fun Project.registerCollectCapsuleContextTask() {
        tasks.register("collectCapsuleContext", CapsuleCompositeContextTask::class.java) { task ->
            task.group = "collect"
            task.description = "Exporte le contexte des capsules (chemins videos + metadonnees) en JSON compatible engine N3"
            task.dependsOn("deployCapsule")
            task.capsuleExtension = this@CapsuleManager.capsuleExt
        }
    }

    private fun Project.registerTransformCapsuleContextTask() {
        tasks.register("transformCapsuleContext", CapsuleParseContextTask::class.java) { task ->
            task.group = "transform"
            task.description = "Parse le fichier capsule-context.json et retourne une liste de decks"
            task.contextFile.convention(
                project.layout.buildDirectory.file("capsule/capsule-context.json")
            )
            task.outputFile.convention(
                project.layout.buildDirectory.file("capsule/capsule-parse-results.json")
            )
        }

        tasks.register("collectCapsuleRetrieve", CapsuleParseContextTask::class.java) { task ->
            task.group = "collect"
            task.description = "Retrieve capsule decks from capsule-context.json (N3 engine contract)"
            val outputFile = project.findProperty("outputFile") as? String
            if (outputFile != null) {
                task.outputFile.set(File(outputFile))
            }
            task.contextFile.convention(
                project.layout.buildDirectory.file("capsule/capsule-context.json")
            )
        }
    }

    companion object {
        fun readScriptFiles(dir: File): List<File> {
            return dir.listFiles { f ->
                f.name.endsWith("-script.txt") &&
                !f.name.startsWith("example-") &&
                !f.name.contains("-context-")
            }
                ?.toList() ?: emptyList()
        }

        fun parseScript(file: File): CapsuleScript {
            val lines = file.readLines()
            val deckName = lines.firstOrNull()
                ?.removePrefix("=== CAPSULE SCRIPT : ")
                ?.removeSuffix(" ===")
                ?.trim() ?: file.nameWithoutExtension

            val slides = mutableListOf<SlideSegment>()
            var currentIndex = -1
            var currentTitle = ""
            val noteLines = mutableListOf<String>()

            for (i in 1 until lines.size) {
                val line = lines[i]
                when {
                    line.startsWith("--- SLIDE ") && line.contains(":") -> {
                        if (currentIndex >= 0) {
                            slides.add(
                                SlideSegment(
                                    index = currentIndex,
                                    title = currentTitle,
                                    speakerNote = noteLines.joinToString("\n").trim()
                                )
                            )
                            noteLines.clear()
                        }
                        val parts = line.removeSurrounding("--- SLIDE ", " ---")
                        val colonIdx = parts.indexOf(":")
                        currentIndex = parts.substring(0, colonIdx).trim()
                            .toIntOrNull() ?: (slides.size + 1)
                        currentTitle = parts.substring(colonIdx + 1).trim()
                    }
                    line.isNotBlank() && currentIndex >= 0 -> noteLines.add(line)
                }
            }

            if (currentIndex >= 0) {
                slides.add(
                    SlideSegment(
                        index = currentIndex,
                        title = currentTitle,
                        speakerNote = noteLines.joinToString("\n").trim()
                    )
                )
            }

            return CapsuleScript(deckName, slides)
        }

        fun resolveScriptDir(project: Project, capsuleExt: CapsuleExtension): File {
            val configured = capsuleExt.sliderScriptDir.get()
            val candidate = project.layout.buildDirectory.dir(configured).get().asFile
            if (candidate.exists() && candidate.listFiles()
                    ?.any { it.name.endsWith("-script.txt") } == true
            ) {
                return candidate
            }
            val sliderOutput = project.rootProject.projectDir.parentFile
                ?.resolve("slider-plugin")
                ?.resolve("slider")
                ?.resolve("build")
                ?.resolve("capsule")
            if (sliderOutput != null && sliderOutput.exists()) return sliderOutput
            return candidate
        }

        fun resolveDeckDir(project: Project, capsuleExt: CapsuleExtension): File {
            val configured = capsuleExt.deckSourceDir.get()
            val candidate = project.layout.buildDirectory.dir(configured).get().asFile
            if (candidate.exists()) return candidate
            val sliderOutput = project.rootProject.projectDir.parentFile
                ?.resolve("slider-plugin")
                ?.resolve("slider")
                ?.resolve("build")
                ?.resolve("docs")
                ?.resolve("asciidocRevealJs")
            if (sliderOutput != null && sliderOutput.exists()) return sliderOutput
            return candidate
        }
    }
}

@DisableCachingByDefault(because = "Filesystem-bound: reads slider output and produces TTS artifacts")
open class CapsuleScriptTask : DefaultTask() {

    @get:OutputDirectory
    val outputDir: DirectoryProperty = project.objects.directoryProperty()

    @get:Internal
    lateinit var capsuleExtension: CapsuleExtension

    init {
        outputDir.convention(project.layout.buildDirectory.dir("capsule"))
    }

    @TaskAction
    fun execute() {
        val scriptDir = CapsuleManager.resolveScriptDir(project, capsuleExtension)
        val scripts = CapsuleManager.readScriptFiles(scriptDir)

        if (scripts.isEmpty()) {
            logger.warn("No *-script.txt files found in {}", scriptDir.absolutePath)
            logger.warn("Run 'asciidocCapsule' from slider-gradle first.")
            return
        }

        for (script in scripts) {
            val parsed = CapsuleManager.parseScript(script)
            logger.lifecycle(
                "Capsule script '{}' → {} slides", parsed.deckName, parsed.slides.size
            )
            for (seg in parsed.slides) {
                logger.lifecycle(
                    "  [{}] {} ({} chars)", seg.index, seg.title, seg.speakerNote.length
                )
            }
        }
    }
}

@DisableCachingByDefault(because = "Filesystem-bound: reads script files and writes TTS placeholder audio")
open class CapsuleBuildTask : DefaultTask() {

    @get:OutputDirectory
    val outputDir: DirectoryProperty = project.objects.directoryProperty()

    @get:Internal
    lateinit var capsuleExtension: CapsuleExtension

    init {
        outputDir.convention(project.layout.buildDirectory.dir("capsule"))
    }

    @get:Internal
    internal var ttsEngine: TtsEngine? = null

    private fun resolveTtsEngine(): TtsEngine {
        if (ttsEngine != null) return ttsEngine!!

        val configuredEngine = capsuleExtension.ttsEngine.get()
        return when (configuredEngine.lowercase()) {
            "piper" -> {
                val piperPath = capsuleExtension.piperExecutablePath.get()
                val voice = capsuleExtension.ttsVoice.get()
                val engine = PiperTtsEngine(piperPath, voice)
                if (engine.isAvailable()) {
                    logger.lifecycle("TTS engine: piper → {}", piperPath)
                    engine
                } else if (capsuleExtension.ttsFallbackEnabled.get()) {
                    logger.warn("Piper not available at {}, falling back to noop placeholder", piperPath)
                    NoOpTtsEngine()
                } else {
                    throw TtsException("Piper not available at: $piperPath and fallback is disabled")
                }
            }
            "espeak" -> {
                val voice = capsuleExtension.espeakVoice.get()
                val speed = capsuleExtension.espeakSpeed.get()
                val engine = EspeakTtsEngine(voice = voice, speed = speed)
                if (engine.isAvailable()) {
                    logger.lifecycle("TTS engine: espeak (voice={}, speed={})", voice, speed)
                    engine
                } else {
                    logger.warn("espeak not available, falling back to noop placeholder")
                    NoOpTtsEngine()
                }
            }
            "noop" -> {
                logger.lifecycle("TTS engine: noop (placeholder)")
                NoOpTtsEngine()
            }
            else -> {
                logger.warn("Unknown TTS engine '{}', using noop placeholder", configuredEngine)
                NoOpTtsEngine()
            }
        }
    }

    @TaskAction
    fun execute() {
        val scriptDir = CapsuleManager.resolveScriptDir(project, capsuleExtension)
        val scripts = CapsuleManager.readScriptFiles(scriptDir)

        if (scripts.isEmpty()) {
            logger.warn("No capsule scripts to process. Skipping TTS generation.")
            return
        }

        val outDir = project.layout.buildDirectory.dir(
            capsuleExtension.outputDir.get()
        ).get().asFile
        outDir.mkdirs()

        val engine = resolveTtsEngine()
        logger.lifecycle("TTS engine: {}", engine.name())

        val cores = Runtime.getRuntime().availableProcessors()
        val executor = Executors.newFixedThreadPool(cores)
        val synthesized = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val futures = mutableListOf<Future<*>>()

        for (script in scripts) {
            val parsed = CapsuleManager.parseScript(script)
            val deckOutputDir = outDir.resolve(parsed.deckName)
            deckOutputDir.mkdirs()

            for (seg in parsed.slides) {
                val idx = String.format("%02d", seg.index)
                val ttsFile = deckOutputDir.resolve("slide-$idx.mp3")

                futures.add(executor.submit {
                    try {
                        engine.synthesize(seg.speakerNote, ttsFile)
                        synthesized.incrementAndGet()
                        logger.lifecycle("  TTS → {} ({} chars)", ttsFile.name, seg.speakerNote.length)
                    } catch (e: TtsException) {
                        failed.incrementAndGet()
                        logger.error("  TTS FAILED slide {}: {}", seg.index, e.message)
                    }
                })
            }
        }

        futures.forEach { it.get() }
        executor.shutdown()

        if (failed.get() > 0 && !capsuleExtension.ttsFallbackEnabled.get()) {
            throw TtsException("${failed.get()} TTS synthesis failures (fallback disabled)")
        }

        logger.lifecycle(
            "TTS generation: {} synthesized, {} failed, {} engine, {} cores",
            synthesized.get(), failed.get(), engine.name(), cores
        )
    }
}

@DisableCachingByDefault(because = "Filesystem-bound: injects audio into HTML deck and captures video via Playwright")
open class CapsuleVideoTask : DefaultTask() {

    @get:OutputDirectory
    val outputDir: DirectoryProperty = project.objects.directoryProperty()

    @get:Internal
    lateinit var capsuleExtension: CapsuleExtension

    @get:Internal
    internal var playwrightCapture: PlaywrightCapture? = null

    @get:Internal
    internal var ttsEngine: TtsEngine? = null

    init {
        outputDir.convention(project.layout.buildDirectory.dir("capsule"))
    }

    companion object {
        private val AUDIO_INJECT_SCRIPT = """
<!-- CAPSULE-GRADLE: Autoplay audio injection -->
<script>
(function() {
  var currentAudio = null;
  var sections = document.querySelectorAll('.reveal .slides section[data-audio]');
  var audios = [];
  sections.forEach(function(sec) {
    var src = sec.getAttribute('data-audio');
    if (src) {
      var audio = new Audio(src.replace('file://', ''));
      audio.id = 'audio-' + audios.length;
      document.body.appendChild(audio);
      audios.push(audio);
    }
  });
  function playSlideAudio(idx) {
    if (currentAudio) { currentAudio.pause(); currentAudio.currentTime = 0; }
    currentAudio = audios[idx];
    if (currentAudio) {
      currentAudio.currentTime = 0;
      currentAudio.play().catch(function(e) { console.warn('Audio play failed:', e); });
    }
  }
  if (typeof Reveal !== 'undefined') {
    Reveal.on('slidechanged', function(event) {
      playSlideAudio(event.indexh);
    });
    if (audios.length > 0) playSlideAudio(0);
  }
})();
</script>
"""
    }

    private fun resolvePlaywrightCapture(slideDurations: List<Double>): PlaywrightCapture {
        if (playwrightCapture != null) return playwrightCapture!!

        val defaultDur = capsuleExtension.slideDurationSeconds.get()
        val impl = PlaywrightCaptureImpl(
            timeout = capsuleExtension.playwrightTimeout.get(),
            defaultSlideDuration = defaultDur
        )
        return if (impl.isAvailable()) {
            val totalSecs = slideDurations.sum()
            logger.lifecycle("Playwright capture: available ({} slides, {}s total)", slideDurations.size, String.format("%.1f", totalSecs))
            impl
        } else {
            logger.warn("Playwright not available, falling back to noop capture")
            NoOpPlaywrightCapture()
        }
    }

    private fun resolveTtsEngine(): TtsEngine {
        if (ttsEngine != null) return ttsEngine!!

        return when (capsuleExtension.ttsEngine.get().lowercase()) {
            "piper" -> {
                val engine = PiperTtsEngine(
                    capsuleExtension.piperExecutablePath.get(),
                    capsuleExtension.ttsVoice.get()
                )
                if (engine.isAvailable()) engine else NoOpTtsEngine()
            }
            "espeak" -> {
                val engine = EspeakTtsEngine(
                    voice = capsuleExtension.espeakVoice.get(),
                    speed = capsuleExtension.espeakSpeed.get()
                )
                if (engine.isAvailable()) engine else NoOpTtsEngine()
            }
            else -> NoOpTtsEngine()
        }
    }

    private fun computeSlideDurations(parsed: CapsuleScript, audioDir: File): List<Double> {
        val defaultDur = capsuleExtension.slideDurationSeconds.get()
        return parsed.slides.map { seg ->
            val idx = String.format("%02d", seg.index)
            val mp3 = audioDir.resolve("slide-$idx.mp3")
            if (mp3.exists()) {
                val realDur = ffprobeDuration(mp3)
                if (realDur > 0.0) realDur else defaultDur
            } else defaultDur
        }
    }

    @TaskAction
    fun execute() {
        val deckDir = CapsuleManager.resolveDeckDir(project, capsuleExtension)
        val scriptDir = CapsuleManager.resolveScriptDir(project, capsuleExtension)

        val deckFiles = deckDir.listFiles { f -> f.name.endsWith("-deck.html") }?.toList()
            ?: emptyList()
        val scripts = CapsuleManager.readScriptFiles(scriptDir)

        if (deckFiles.isEmpty()) {
            logger.warn("No *-deck.html files found in {}", deckDir.absolutePath)
            logger.warn("Run 'asciidoctorRevealJs' from slider-gradle first.")
            return
        }

        if (scripts.isEmpty()) {
            logger.warn("No capsule scripts found. Run 'asciidocCapsule' from slider-gradle first.")
            return
        }

        val outDir = project.layout.buildDirectory.dir(
            capsuleExtension.outputDir.get()
        ).get().asFile
        outDir.mkdirs()

        val engine = resolveTtsEngine()

        for (script in scripts) {
            val parsed = CapsuleManager.parseScript(script)
            val audioDir = outDir.resolve(parsed.deckName)
            audioDir.mkdirs()

            for (seg in parsed.slides) {
                val idx = String.format("%02d", seg.index)
                val ttsFile = audioDir.resolve("slide-$idx.mp3")
                if (!ttsFile.exists()) {
                    try {
                        engine.synthesize(seg.speakerNote, ttsFile)
                        logger.lifecycle("  TTS → {} ({} chars)", ttsFile.name, seg.speakerNote.length)
                    } catch (e: TtsException) {
                        logger.warn("  TTS SKIP slide {}: {}", seg.index, e.message)
                    }
                }
            }

            val deckFile = deckFiles.find { it.nameWithoutExtension.startsWith(parsed.deckName) }
                ?: deckFiles.firstOrNull()
            if (deckFile == null) {
                logger.warn("No matching deck HTML found for '{}'", parsed.deckName)
                continue
            }

            val modifiedDeck = injectAudio(deckFile, parsed, audioDir)
            val videoOutputDir = outDir.resolve(parsed.deckName).resolve("video")
            videoOutputDir.mkdirs()

            val slideDurations = computeSlideDurations(parsed, audioDir)
            val cores = Runtime.getRuntime().availableProcessors()
            logger.lifecycle(
                "Parallel capture: {} slides on {} cores", parsed.slides.size, cores
            )

            val slideWebms = captureSlideParallel(
                modifiedDeck, parsed, slideDurations, videoOutputDir, cores
            )

            if (slideWebms.isNotEmpty()) {
                val finalVideo = outDir.resolve("${parsed.deckName}.webm")
                concatWebmFiles(slideWebms, finalVideo)
                mixAudioWithVideo(finalVideo, audioDir, parsed.slides, capsuleExtension.slideDurationSeconds.get())
                logger.lifecycle("CAPSULE → {}", finalVideo.absolutePath)
            } else {
                logger.warn("No video segments produced for '{}'", parsed.deckName)
            }
        }
    }

    private fun captureSlideParallel(
        deck: File,
        script: CapsuleScript,
        slideDurations: List<Double>,
        outputDir: File,
        cores: Int
    ): List<File> {
        // Cap parallel Playwright instances: each Chromium needs ~300 MB RAM + CPU startup.
        // Beyond 4 simultaneous instances the gains plateau and timeouts start appearing.
        val parallelism = minOf(cores, 4)
        val executor = Executors.newFixedThreadPool(parallelism)
        val futures = mutableListOf<Future<File?>>()

        val playwrightTimeout = capsuleExtension.playwrightTimeout.get()
        val viewportWidth    = capsuleExtension.viewportWidth.get()
        val viewportHeight   = capsuleExtension.viewportHeight.get()
        val defaultDur       = capsuleExtension.slideDurationSeconds.get()

        for ((i, seg) in script.slides.withIndex()) {
            val duration  = slideDurations.getOrElse(i) { defaultDur }
            val slideDir  = outputDir.resolve("slide-${String.format("%02d", seg.index)}").also { it.mkdirs() }
            val slideHtml = createSingleSlideHtml(deck, i)

            futures.add(executor.submit<File?> {
                val capture = ScreenshotCaptureImpl(timeout = playwrightTimeout)
                return@submit try {
                    capture.capture(
                        deckHtmlPath = slideHtml.absolutePath,
                        outputDir = slideDir,
                        viewportWidth = viewportWidth,
                        viewportHeight = viewportHeight,
                        slideDurations = listOf(duration)
                    )
                    capture.close()
                    val webm = slideDir.resolve("slide.webm").takeIf { it.exists() }
                    logger.lifecycle("  ✓ slide {} ({}s)", seg.index, String.format("%.1f", duration))
                    webm
                } catch (e: Exception) {
                    capture.close()
                    logger.error("  ✗ slide {} failed: {}", seg.index, e.message)
                    null
                }
            })
        }

        executor.shutdown()
        return futures.mapNotNull { it.get() }
    }

    private fun createSingleSlideHtml(deck: File, slideIndex: Int): File {
        val content = deck.readText()
        val isolateScript = """
<script>
(function() {
  function isolate(idx) {
    var sections = document.querySelectorAll('.reveal .slides > section, .slides > section');
    sections.forEach(function(s, i) {
      s.style.visibility = (i === idx) ? 'visible' : 'hidden';
      s.style.position   = (i === idx) ? 'relative' : 'absolute';
      s.style.display    = (i === idx) ? ''         : 'none';
    });
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function() { isolate($slideIndex); });
  } else {
    isolate($slideIndex);
  }
})();
</script>
"""
        val injected = content.replace("</head>", "$isolateScript</head>")
        val outFile = deck.parentFile.resolve("slide-${String.format("%02d", slideIndex)}-${deck.name}")
        outFile.writeText(injected)
        return outFile
    }

    private fun concatWebmFiles(files: List<File>, output: File) {
        if (files.size == 1) {
            files[0].copyTo(output, overwrite = true)
            return
        }
        val concatList = output.parentFile.resolve("concat-list.txt")
        concatList.writeText(files.joinToString("\n") { "file '${it.absolutePath}'" })

        val proc = ProcessBuilder(
            "ffmpeg", "-y", "-f", "concat", "-safe", "0",
            "-i", concatList.absolutePath,
            "-c", "copy",
            output.absolutePath
        ).redirectErrorStream(true).start()

        val exitCode = proc.waitFor()
        if (exitCode != 0) {
            val err = proc.inputStream.bufferedReader().readText()
            throw RuntimeException("FFmpeg concat failed (exit $exitCode): $err")
        }
        concatList.delete()
        logger.lifecycle("  Concat: {} segments → {}", files.size, output.name)
    }

    private fun injectAudio(deckFile: File, script: CapsuleScript, audioDir: File): File {
        val originalHtml = deckFile.readText()
        val injectedDir = project.layout.buildDirectory.dir("capsule/injected").get().asFile
        injectedDir.mkdirs()

        val hasAudio = script.slides.any { seg ->
            val idx = String.format("%02d", seg.index)
            val audioFile = audioDir.resolve("slide-$idx.mp3")
            audioFile.exists()
        }

        if (!hasAudio) {
            val outFile = injectedDir.resolve(deckFile.name)
            outFile.writeText(originalHtml)
            return outFile
        }

        val hasDataCapsuleSlide = originalHtml.contains("data-capsule-slide=")

        val injectedHtml = originalHtml.lines().map { line ->
            if (line.contains("<section") && !line.contains("</section>")) {
                var mutableLine = line
                for (seg in script.slides) {
                    if (line.contains("data-capsule-slide=\"${seg.index}\"") || line.contains("data-capsule-slide='${seg.index}'")) {
                        val idx = String.format("%02d", seg.index)
                        val audioPath = audioDir.resolve("slide-$idx.mp3").absolutePath
                        mutableLine = mutableLine.replace(
                            "<section",
                            "<section data-audio=\"file://$audioPath\""
                        )
                        break
                    }
                }
                mutableLine
            } else {
                line
            }
        }.joinToString("\n")

        if (!hasDataCapsuleSlide) {
            return injectAudioSequentialFallback(deckFile, script, audioDir, injectedDir)
        }

        val injected = injectedHtml.replace(
            "</body>",
            "$AUDIO_INJECT_SCRIPT</body>"
        )

        val outFile = injectedDir.resolve(deckFile.name)
        outFile.writeText(injected)
        return outFile
    }

    private fun injectAudioSequentialFallback(
        deckFile: File,
        script: CapsuleScript,
        audioDir: File,
        injectedDir: File
    ): File {
        val originalHtml = deckFile.readText()

        val slideRegex = Regex("""<section\b[^>]*>""")
        val sections = slideRegex.findAll(originalHtml).toList()

        val injectedHtml = buildString {
            var lastEnd = 0
            var slideIdx = 0
            for (match in sections) {
                append(originalHtml.substring(lastEnd, match.range.first))
                var tag = match.value
                if (slideIdx < script.slides.size) {
                    val seg = script.slides[slideIdx]
                    val idx = String.format("%02d", seg.index)
                    val audioPath = audioDir.resolve("slide-$idx.mp3").absolutePath
                    tag = tag.replace("<section", "<section data-audio=\"file://$audioPath\"")
                }
                append(tag)
                lastEnd = match.range.last + 1
                slideIdx++
            }
            append(originalHtml.substring(lastEnd))
        }

        val sequentialScript = AUDIO_INJECT_SCRIPT.replace(
            "<!-- CAPSULE-GRADLE: Autoplay audio injection -->",
            "<!-- CAPSULE-GRADLE: Autoplay audio injection (sequential fallback) -->"
        )

        val injected = injectedHtml.replace(
            "</body>",
            "$sequentialScript</body>"
        )

        val outFile = injectedDir.resolve(deckFile.name)
        outFile.writeText(injected)
        return outFile
    }

    private fun ffprobeDuration(file: File): Double {
        return try {
            val proc = ProcessBuilder("ffprobe", "-v", "quiet", "-show_entries", "format=duration", "-of", "csv=p=0", file.absolutePath)
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            out.toDoubleOrNull() ?: 0.0
        } catch (_: Exception) { 0.0 }
    }

    private fun mixAudioWithVideo(videoFile: File, audioDir: File, slides: List<SlideSegment>, slideDurationSeconds: Double) {
        val mp3Files = slides.mapNotNull { seg ->
            val idx = String.format("%02d", seg.index)
            val f = audioDir.resolve("slide-$idx.mp3")
            f.takeIf { it.exists() }
        }
        if (mp3Files.isEmpty()) return

        val cmd = mutableListOf("ffmpeg", "-y", "-i", videoFile.absolutePath)
        val concatInputs = mutableListOf<String>()

        for ((i, mp3) in mp3Files.withIndex()) {
            val inputIdx = i + 1
            cmd.addAll(listOf("-i", mp3.absolutePath))
            concatInputs.add("[$inputIdx:a]")
        }

        val filterComplex = "${concatInputs.joinToString("")}concat=n=${mp3Files.size}:v=0:a=1[aout]"
        cmd.addAll(listOf("-filter_complex", filterComplex, "-map", "0:v", "-map", "[aout]", "-c:v", "copy", "-c:a", "libvorbis", "-shortest"))

        val tmpFile = File(videoFile.absolutePath + ".tmp.webm")
        cmd.add(tmpFile.absolutePath)

        try {
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val exitCode = proc.waitFor()
            if (exitCode == 0 && tmpFile.exists()) {
                tmpFile.renameTo(videoFile)
                val totalSlides = mp3Files.size
                val audioDur = mp3Files.sumOf { f -> ffprobeDuration(f) }
                logger.lifecycle("  Audio mix: {} slides concatenated (audio={}s, video={}s)", totalSlides, String.format("%.1f", audioDur), String.format("%.1f", ffprobeDuration(videoFile)))
            } else {
                logger.warn("  Audio mix failed (ffmpeg exit code {}), video remains silent", exitCode)
                tmpFile.delete()
            }
        } catch (e: Exception) {
            logger.warn("  Audio mix error: {} — video remains silent", e.message)
            tmpFile.delete()
        }
    }
}

@DisableCachingByDefault(because = "Filesystem-bound: invokes FFmpeg process for video cropping")
open class CapsuleDistribTask : DefaultTask() {

    @get:OutputDirectory
    val outputDir: DirectoryProperty = project.objects.directoryProperty()

    @get:Internal
    lateinit var capsuleExtension: CapsuleExtension

    init {
        outputDir.convention(project.layout.buildDirectory.dir("capsule/distrib"))
    }

    @TaskAction
    fun execute() {
        val capDir = project.layout.buildDirectory.dir(
            capsuleExtension.outputDir.get()
        ).get().asFile

        val videos = capDir.listFiles { f -> f.name.endsWith(".webm") }?.toList()
            ?: emptyList()

        if (videos.isEmpty()) {
            logger.warn("No capsule videos found in {}. Run 'generateCapsuleVideo' first.", capDir.absolutePath)
            return
        }

        val distDir = outputDir.get().asFile
        distDir.mkdirs()

        val ffmpeg = capsuleExtension.ffmpegExecutablePath.get()
        val targetWidth = capsuleExtension.distribOutputWidth.get()
        val targetHeight = capsuleExtension.distribOutputHeight.get()

        if (!isFfmpegAvailable(ffmpeg)) {
            logger.warn("FFmpeg not available at '{}' — copying videos as-is to distrib", ffmpeg)
            for (video in videos) {
                val dest = distDir.resolve(video.name)
                video.copyTo(dest, overwrite = true)
                logger.lifecycle("  COPY → {}", dest.absolutePath)
            }
            return
        }

        for (video in videos) {
            val outputFile = distDir.resolve(video.name)

            if (!isValidWebM(video)) {
                logger.lifecycle("DISTRIB → {} (placeholder, copy as-is)", video.name)
                video.copyTo(outputFile, overwrite = true)
                logger.lifecycle("  COPY → {}", outputFile.absolutePath)
                continue
            }

            logger.lifecycle("DISTRIB → {} (crop {}x{})", video.name, targetWidth, targetHeight)

            try {
                cropVideo(video, outputFile, ffmpeg, targetWidth, targetHeight)
                logger.lifecycle("  OK → {}", outputFile.absolutePath)
            } catch (e: Exception) {
                logger.error("  FAILED {}: {}", video.name, e.message)
                throw e
            }
        }
    }

    private fun cropVideo(
        input: File,
        output: File,
        ffmpeg: String,
        targetWidth: Int,
        targetHeight: Int
    ) {
        val proc = ProcessBuilder(
            ffmpeg, "-y",
            "-i", input.absolutePath,
            "-vf", "scale=$targetWidth:$targetHeight:force_original_aspect_ratio=increase,crop=$targetWidth:$targetHeight",
            "-c:a", "copy",
            output.absolutePath
        ).redirectErrorStream(true).start()

        val exitCode = proc.waitFor()
        if (exitCode != 0) {
            val stderr = proc.inputStream.bufferedReader().readText()
            throw RuntimeException("FFmpeg exited with code $exitCode: $stderr")
        }
    }

    private fun isFfmpegAvailable(ffmpegPath: String): Boolean {
        return try {
            val proc = ProcessBuilder(ffmpegPath, "-version")
                .redirectErrorStream(true)
                .start()
            proc.waitFor()
            proc.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }

    private val webmSignature = byteArrayOf(0x1a.toByte(), 0x45.toByte(), 0xdf.toByte(), 0xa3.toByte())

    private fun isValidWebM(file: File): Boolean {
        if (file.length() < 4) return false
        return try {
            val header = ByteArray(4)
            file.inputStream().use { it.read(header) }
            header.contentEquals(webmSignature)
        } catch (e: Exception) {
            false
        }
    }
}

@DisableCachingByDefault(because = "Filesystem-bound: scans capsule output directory and generates JSON context")
open class CapsuleCompositeContextTask : DefaultTask() {

    @get:OutputFile
    val outputFile: RegularFileProperty = project.objects.fileProperty()

    @get:Internal
    lateinit var capsuleExtension: CapsuleExtension

    init {
        outputFile.convention(
            project.layout.buildDirectory.file("capsule/capsule-context.json")
        )
    }

    @TaskAction
    fun execute() {
        val capDir = project.layout.buildDirectory.dir(
            capsuleExtension.outputDir.get()
        ).get().asFile
        val distDir = project.layout.buildDirectory.dir("capsule/distrib").get().asFile
        val scriptDir = project.layout.buildDirectory.dir(
            capsuleExtension.sliderScriptDir.get()
        ).get().asFile

        val scripts = CapsuleManager.readScriptFiles(scriptDir)

        val capsuleEntries = mutableListOf<Map<String, Any>>()

        for (script in scripts) {
            val parsed = CapsuleManager.parseScript(script)
            val deckName = parsed.deckName

            val originalVideo = capDir.resolve("$deckName.webm")
            val distribVideo = distDir.resolve("$deckName.webm")

            val slideInfos = parsed.slides.map { seg ->
                mapOf(
                    "index" to seg.index,
                    "title" to seg.title,
                    "speakerNoteLength" to seg.speakerNote.length
                )
            }

            capsuleEntries.add(mapOf<String, Any>(
                "source" to "capsule",
                "deckName" to deckName,
                "slideCount" to parsed.slides.size,
                "originalVideo" to originalVideo.absolutePath,
                "distribVideo" to (if (distribVideo.exists()) distribVideo.absolutePath else ""),
                "viewport" to mapOf(
                    "width" to capsuleExtension.viewportWidth.get(),
                    "height" to capsuleExtension.viewportHeight.get()
                ),
                "distribDimensions" to mapOf(
                    "width" to capsuleExtension.distribOutputWidth.get(),
                    "height" to capsuleExtension.distribOutputHeight.get()
                ),
                "slides" to slideInfos,
                "ttsEngine" to capsuleExtension.ttsEngine.get(),
                "ttsVoice" to capsuleExtension.ttsVoice.get()
            ))
        }

        val result = mapOf(
            "source" to "capsule",
            "version" to (project.version as String),
            "entries" to capsuleEntries,
            "timestamp" to System.currentTimeMillis()
        )

        val mapper = jacksonObjectMapper()
        val outFile = outputFile.get().asFile
        outFile.parentFile.mkdirs()
        mapper.writerWithDefaultPrettyPrinter().writeValue(outFile, result)

        logger.lifecycle(
            "CAPSULE COMPOSITE CONTEXT -> {} ({} decks)",
            outFile.absolutePath, capsuleEntries.size
        )
    }
}

@DisableCachingByDefault(because = "Filesystem-bound: reads capsule context JSON and writes parsed results")
open class CapsuleParseContextTask : DefaultTask() {

    @get:Internal
    val contextFile: RegularFileProperty = project.objects.fileProperty()

    @get:OutputFile
    val outputFile: RegularFileProperty = project.objects.fileProperty()

    @TaskAction
    fun execute() {
        val input = contextFile.asFile.get()
        val output = outputFile.asFile.get()
        output.parentFile.mkdirs()

        if (!input.exists()) {
            logger.warn("capsule-context.json not found at {}, returning empty list", input.absolutePath)
            val mapper = jacksonObjectMapper()
            mapper.writerWithDefaultPrettyPrinter().writeValue(output, emptyList<Map<String, Any>>())
            logger.lifecycle("CAPSULE PARSE CONTEXT -> {} (0 decks, no input file)", output.absolutePath)
            return
        }

        val mapper = jacksonObjectMapper()
        val root: Map<String, Any> = mapper.readValue(input)

        @Suppress("UNCHECKED_CAST")
        val entries = root["entries"] as? List<Map<String, Any>> ?: emptyList()

        val results = entries.map { entry ->
            mapOf<String, Any>(
                "source" to "capsule",
                "deckName" to (entry["deckName"]?.toString() ?: ""),
                "slideCount" to ((entry["slideCount"] as? Number)?.toInt() ?: 0),
                "originalVideo" to (entry["originalVideo"]?.toString() ?: ""),
                "distribVideo" to (entry["distribVideo"]?.toString() ?: ""),
                "ttsEngine" to (entry["ttsEngine"]?.toString() ?: ""),
                "ttsVoice" to (entry["ttsVoice"]?.toString() ?: "")
            )
        }

        mapper.writerWithDefaultPrettyPrinter().writeValue(output, results)
        logger.lifecycle("CAPSULE PARSE CONTEXT -> {} ({} decks)", output.absolutePath, results.size)
    }
}
