package capsule

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import java.io.File
import java.nio.file.Paths
import java.nio.file.Path

interface PlaywrightCapture {
    fun capture(deckHtmlPath: String, outputDir: File, viewportWidth: Int, viewportHeight: Int, slideDurations: List<Double>)
    fun isAvailable(): Boolean
    fun name(): String
    fun close()
}

class PlaywrightCaptureImpl(
    private val timeout: Double = 120_000.0,
    private val transitionPause: Double = 500.0,
    private val endMargin: Double = 2000.0,
    private val defaultSlideDuration: Double = 5.0
) : PlaywrightCapture {

    private var playwright: Playwright? = null
    private var browser: Browser? = null
    private var context: BrowserContext? = null
    private var page: Page? = null

    override fun isAvailable(): Boolean = try {
        Playwright.create().use { pw ->
            pw.chromium().launch(BrowserType.LaunchOptions().setHeadless(true)).use { it.close() }
        }
        true
    } catch (e: Exception) {
        false
    }

    override fun name(): String = "playwright-java"

    override fun capture(
        deckHtmlPath: String,
        outputDir: File,
        viewportWidth: Int,
        viewportHeight: Int,
        slideDurations: List<Double>
    ) {
        val slideCount = slideDurations.size
        playwright = Playwright.create()
        browser = playwright!!.chromium().launch(
            BrowserType.LaunchOptions().setHeadless(true)
        )
        context = browser!!.newContext(
            Browser.NewContextOptions()
                .setViewportSize(viewportWidth, viewportHeight)
                .setRecordVideoDir(Paths.get(outputDir.absolutePath))
                .setRecordVideoSize(viewportWidth, viewportHeight)
        )
        page = context!!.newPage()
        page!!.setDefaultNavigationTimeout(timeout)
        page!!.setDefaultTimeout(timeout)

        val absolutePath = File(deckHtmlPath).absolutePath
        page!!.navigate("file://$absolutePath")

        page!!.waitForSelector(".reveal",
            Page.WaitForSelectorOptions().setTimeout(timeout))

        page!!.waitForTimeout(2000.0)

        val audioIds = page!!.evaluate("Array.from(document.querySelectorAll('audio')).map(a => a.id)") as? List<*> ?: listOf<Any>()
        val hasAudioElements = audioIds.isNotEmpty()

        println("  Playwright: hasAudio=${hasAudioElements} audioCount=${audioIds.size} slides=$slideCount")

        for (i in 0 until slideCount) {
            val slideMs = (slideDurations[i] * 1000).toLong()
            // Always use waitForTimeout: audio doesn't play in headless Chromium.
            // FFmpeg will mix the audio tracks onto the captured video later.
            page!!.waitForTimeout(slideMs.toDouble())
            page!!.waitForTimeout(transitionPause)
            if (i < slideCount - 1) {
                page!!.evaluate("typeof Reveal !== 'undefined' && Reveal.next()")
            }
        }

        page!!.waitForTimeout(endMargin)
    }

    override fun close() {
        context?.close()
        browser?.close()
        playwright?.close()
        context = null
        browser = null
        playwright = null
        page = null
    }
}

class NoOpPlaywrightCapture : PlaywrightCapture {
    override fun isAvailable(): Boolean = true
    override fun name(): String = "noop-playwright"

    override fun capture(
        deckHtmlPath: String,
        outputDir: File,
        viewportWidth: Int,
        viewportHeight: Int,
        slideDurations: List<Double>
    ) {
        outputDir.mkdirs()
        val placeholder = listOf(
            "# PLAYWRIGHT CAPTURE PLACEHOLDER (noop engine)",
            "# Deck: $deckHtmlPath",
            "# Slides: ${slideDurations.size}",
            "# Viewport: ${viewportWidth}x$viewportHeight"
        ).joinToString("\n")
        outputDir.resolve("capsule.webm").writeText(placeholder)
    }

    override fun close() {}
}

class CapturingException(message: String) : RuntimeException(message)

/**
 * Screenshot-based capture: takes a PNG of each slide then uses FFmpeg to produce
 * a WebM of the exact audio duration. No real-time recording — orders of magnitude
 * faster and more reliable than Playwright video recording.
 */
class ScreenshotCaptureImpl(
    private val timeout: Double = 120_000.0
) : PlaywrightCapture {

    private var playwright: Playwright? = null
    private var browser: Browser? = null

    override fun isAvailable(): Boolean = try {
        Playwright.create().use { pw ->
            pw.chromium().launch(BrowserType.LaunchOptions().setHeadless(true)).use { it.close() }
        }
        true
    } catch (_: Exception) { false }

    override fun name(): String = "screenshot+ffmpeg"

    override fun capture(
        deckHtmlPath: String,
        outputDir: File,
        viewportWidth: Int,
        viewportHeight: Int,
        slideDurations: List<Double>
    ) {
        val duration = slideDurations.firstOrNull() ?: 5.0
        val absolutePath = File(deckHtmlPath).absolutePath

        playwright = Playwright.create()
        browser = playwright!!.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))

        val page = browser!!.newPage(
            Browser.NewPageOptions().setViewportSize(viewportWidth, viewportHeight)
        )
        page.setDefaultNavigationTimeout(timeout)
        page.setDefaultTimeout(timeout)
        page.navigate("file://$absolutePath")
        page.waitForSelector(".reveal, section, body",
            Page.WaitForSelectorOptions().setTimeout(timeout))
        page.waitForTimeout(800.0)

        val png = outputDir.resolve("slide.png")
        page.screenshot(Page.ScreenshotOptions().setPath(png.toPath()))
        page.close()

        // FFmpeg: PNG → WebM of exact duration (1 static frame)
        val webm = outputDir.resolve("slide.webm")
        val proc = ProcessBuilder(
            "ffmpeg", "-y",
            "-loop", "1",
            "-framerate", "1",
            "-i", png.absolutePath,
            "-t", duration.toString(),
            "-c:v", "libvpx",
            "-b:v", "500k",
            "-vf", "scale=$viewportWidth:$viewportHeight",
            "-pix_fmt", "yuv420p",
            "-auto-alt-ref", "0",
            webm.absolutePath
        ).redirectErrorStream(true).start()

        val exitCode = proc.waitFor()
        if (exitCode != 0) {
            val err = proc.inputStream.bufferedReader().readText()
            throw CapturingException("FFmpeg PNG→WebM failed (slide): $err")
        }
    }

    override fun close() {
        browser?.close()
        playwright?.close()
        browser = null
        playwright = null
    }
}
