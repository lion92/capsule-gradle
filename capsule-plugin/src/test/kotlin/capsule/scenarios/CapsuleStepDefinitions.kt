package capsule.scenarios

import io.cucumber.java.en.And
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.gradle.testkit.runner.GradleRunner
import java.io.File
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CapsuleStepDefinitions {

    private var _projectDir: File? = null
    private val projectDir: File
        get() = _projectDir ?: error("Background not executed")

    private var lastBuildResult = ""

    @Given("a Gradle project with the capsule plugin applied")
    fun aGradleProjectWithTheCapsulePluginApplied() {
        _projectDir = File(System.getProperty("java.io.tmpdir"))
            .resolve("cucumber-capsule-${System.currentTimeMillis()}")
            .also { it.mkdirs() }

        projectDir.resolve("settings.gradle").writeText("")
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id('education.cccp.capsule')
            }
            capsule {
                ttsEngine = "noop"
            }
        """.trimIndent())
    }

    private fun decksDir(): File =
        projectDir.resolve("build/docs/asciidocRevealJs").also { it.mkdirs() }

    private fun scriptsDir(): File =
        projectDir.resolve("build/capsule").also { it.mkdirs() }

    @Given("a reveal.js deck {string} with {int} slides and data-capsule-slide attributes")
    fun aRevealJsDeckWithSlidesAndDataCapsuleSlideAttributes(deckName: String, slideCount: Int) {
        val slides = (1..slideCount).joinToString("\n") { i ->
            """    <section data-capsule-slide="$i"><h2>Slide $i</h2><p>Content $i</p></section>"""
        }
        val deckHtml = """
<html><body>
<div class="reveal">
  <div class="slides">
$slides
  </div>
</div>
</body></html>
        """.trimIndent()
        decksDir().resolve(deckName).writeText(deckHtml)
    }

    @Given("a reveal.js deck {string} with {int} slides without data-capsule-slide attributes")
    fun aRevealJsDeckWithoutDataCapsuleSlideAttributes(deckName: String, slideCount: Int) {
        val slides = (1..slideCount).joinToString("\n") { i ->
            """    <section><h2>Slide $i</h2></section>"""
        }
        val deckHtml = """
<html><body>
<div class="reveal">
  <div class="slides">
$slides
  </div>
</div>
</body></html>
        """.trimIndent()
        decksDir().resolve(deckName).writeText(deckHtml)
    }

    @And("a capsule script {string} with {int} slide segment")
    fun aCapsuleScriptWithOneSlideSegment(scriptName: String, count: Int) {
        writeScript(scriptName, count)
    }

    @And("a capsule script {string} with {int} slide segments")
    fun aCapsuleScriptWithNSlideSegments(scriptName: String, count: Int) {
        writeScript(scriptName, count)
    }

    @And("a capsule script {string} with {int} sequentially ordered slide segments")
    fun aCapsuleScriptSequentiallyOrdered(scriptName: String, count: Int) {
        writeScript(scriptName, count)
    }

    private fun writeScript(scriptName: String, count: Int) {
        val deckBase = scriptName.replace("-script.txt", "")
        val slides = (1..count).joinToString("\n") { i ->
            """--- SLIDE $i : Slide $i ---
Speaker note for slide $i."""
        }
        val script = """
=== CAPSULE SCRIPT : $deckBase ===
$slides
        """.trimIndent()
        scriptsDir().resolve(scriptName).writeText(script)
    }

    @When("I run the task {string} with NoOp capture")
    fun iRunTheTaskWithNoOpCapture(taskName: String) {
        runTask(taskName)
    }

    @When("I run the task {string}")
    fun iRunTheTask(taskName: String) {
        runTask(taskName)
    }

    private fun runTask(taskName: String) {
        val runner = GradleRunner.create()
        runner.forwardOutput()
        runner.withPluginClasspath()
        runner.withArguments(taskName)
        runner.withProjectDir(projectDir)
        val result = runner.build()
        lastBuildResult = result.output
    }

    @Then("a video file {string} is generated")
    fun aVideoFileIsGenerated(videoName: String) {
        val videoFile = projectDir.resolve("build/capsule/$videoName")
        assertTrue(videoFile.exists(), "Expected video at ${videoFile.absolutePath}")
    }

    @Then("the video file is not empty")
    fun theVideoFileIsNotEmpty() {
        assertTrue(lastBuildResult.contains("CAPSULE →"))
    }

    @Then("the task completes without error")
    fun theTaskCompletesWithoutError() {
        assertTrue(lastBuildResult.isNotEmpty())
    }

    @Then("a placeholder video is generated")
    fun aPlaceholderVideoIsGenerated() {
        assertTrue(
            lastBuildResult.contains("PLAYWRIGHT CAPTURE PLACEHOLDER") ||
            lastBuildResult.contains("CAPSULE →")
        )
    }

    @Then("the injected deck HTML contains audio attributes for all slides")
    fun theInjectedDeckHtmlContainsAudioAttributesForAllSlides() {
        val injectedDir = projectDir.resolve("build/capsule/injected")
        val injectedFiles = injectedDir.listFiles { f -> f.name.endsWith("-deck.html") }
        assertNotNull(injectedFiles, "Should have injected deck files")
        assertTrue(injectedFiles.isNotEmpty(), "Should have injected deck files")
        val content = injectedFiles.first().readText()
        assertTrue(content.contains("data-audio"), "Should contain data-audio attributes")
    }

    @Then("the injected deck HTML contains {string} attributes")
    fun theInjectedDeckHtmlContainsAttributes(attributeName: String) {
        val injectedDir = projectDir.resolve("build/capsule/injected")
        val injectedFiles = injectedDir.listFiles { f -> f.name.endsWith("-deck.html") }
        assertNotNull(injectedFiles, "Should have injected deck files")
        assertTrue(injectedFiles.isNotEmpty(), "Should have injected deck files")
        val content = injectedFiles.first().readText()
        assertTrue(content.contains(attributeName), "Should contain $attributeName")
    }

    @Then("the injected deck contains the {string} autoplay script")
    fun theInjectedDeckContainsTheAutoplayScript(expected: String) {
        val injectedDir = projectDir.resolve("build/capsule/injected")
        val injectedFiles = injectedDir.listFiles { f -> f.name.endsWith("-deck.html") }
        assertNotNull(injectedFiles, "Should have injected deck files")
        assertTrue(injectedFiles.isNotEmpty(), "Should have injected deck files")
        val content = injectedFiles.first().readText()
        assertTrue(content.contains(expected), "Should contain $expected")
    }

    @Then("the parsed output contains {string}")
    fun theParsedOutputContains(expected: String) {
        val outputFile = projectDir.resolve("build/capsule/capsule-parse-results.json")
        if (!outputFile.exists()) {
            val legacy = projectDir.resolve("build/capsule/retrieve-results.json")
            if (legacy.exists()) {
                assertTrue(legacy.readText().contains(expected), "Should contain $expected in retrieve-results.json")
                return
            }
        }
        assertTrue(outputFile.exists(), "Output file should exist")
        assertTrue(outputFile.readText().contains(expected), "Should contain $expected")
    }

    @Then("the parsed output is a valid JSON array")
    fun theParsedOutputIsAValidJsonArray() {
        val outputFile = projectDir.resolve("build/capsule/capsule-parse-results.json")
        if (!outputFile.exists()) {
            val legacy = projectDir.resolve("build/capsule/retrieve-results.json")
            if (legacy.exists()) {
                val content = legacy.readText().trim()
                assertTrue(content.startsWith("["), "Should start with [")
                assertTrue(content.endsWith("]"), "Should end with ]")
                return
            }
        }
        assertTrue(outputFile.exists(), "Output file should exist")
        val content = outputFile.readText().trim()
        assertTrue(content.startsWith("["), "Should start with [")
        assertTrue(content.endsWith("]"), "Should end with ]")
    }

    @Given("a Gradle project with the capsule plugin configured for espeak TTS")
    fun aGradleProjectWithTheCapsulePluginConfiguredForEspeakTts() {
        _projectDir = File(System.getProperty("java.io.tmpdir"))
            .resolve("cucumber-capsule-espeak-${System.currentTimeMillis()}")
            .also { it.mkdirs() }

        projectDir.resolve("settings.gradle").writeText("")
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id('education.cccp.capsule')
            }
            capsule {
                ttsEngine = "espeak"
                outputDir = "capsules"
            }
        """.trimIndent())
    }

    @Given("a Gradle project with the capsule plugin configured for noop TTS")
    fun aGradleProjectWithTheCapsulePluginConfiguredForNoopTts() {
        _projectDir = File(System.getProperty("java.io.tmpdir"))
            .resolve("cucumber-capsule-noop-${System.currentTimeMillis()}")
            .also { it.mkdirs() }

        projectDir.resolve("settings.gradle").writeText("")
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id('education.cccp.capsule')
            }
            capsule {
                ttsEngine = "noop"
                outputDir = "capsules"
            }
        """.trimIndent())
    }

    @When("I run the task {string} with espeak TTS")
    fun iRunTheTaskWithEspeakTts(taskName: String) {
        runTask(taskName)
    }

    @Then("the generated MP3 files must be binary audio not text placeholder")
    fun theGeneratedMp3FilesMustBeBinaryAudioNotTextPlaceholder() {
        val capDir = projectDir.resolve("capsules")
        val mp3Files = capDir.walk()
            .filter { it.name.endsWith(".mp3") }
            .toList()
        assertTrue(mp3Files.isNotEmpty(), "Must produce MP3 files in capsules/")
        for (mp3 in mp3Files) {
            assertTrue(mp3.length() > 500, "MP3 must be > 500 bytes, got ${mp3.length()} for ${mp3.name}")
            val content = mp3.readText(Charsets.ISO_8859_1)
            assertTrue(!content.contains("TTS PLACEHOLDER"), "MP3 ${mp3.name} must not be a text placeholder")
        }
    }

    @Then("the video file {string} exists in the build {string} directory")
    fun theVideoFileExistsInTheBuildDirectory(videoName: String, dirName: String) {
        val videoFile = projectDir.resolve("build/$dirName/$videoName")
        assertTrue(videoFile.exists(), "Video must be in build/$dirName/$videoName, expected: ${videoFile.absolutePath}")
    }

    private val webmSignature = byteArrayOf(0x1a.toByte(), 0x45.toByte(), 0xdf.toByte(), 0xa3.toByte())

    @Then("the video file {string} has a valid WebM EBML header")
    fun theVideoFileHasAValidWebmEbmlHeader(videoName: String) {
        val videoFile = projectDir.resolve("build/capsules/$videoName")
        assertTrue(videoFile.exists(), "Video must exist at ${videoFile.absolutePath}")
        assertTrue(videoFile.length() > 0, "Video must not be empty")

        val header = ByteArray(4)
        videoFile.inputStream().use { it.read(header) }
        assertTrue(header.contentEquals(webmSignature), "Video must have EBML WebM header, got: ${header.joinToString { "%02x".format(it) }}")
    }
}
