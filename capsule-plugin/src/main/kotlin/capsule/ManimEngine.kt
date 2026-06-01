package capsule

import java.io.File

/**
 * Renders a Manim Python scene to a WebM/MP4 video file.
 *
 * The script file must contain a class named [sceneName] that extends Scene.
 * Manim is invoked as a subprocess; the output video is moved to [outputFile].
 */
class ManimEngine(
    private val manimExecutable: String = "manim",
    private val quality: String = "l"   // l=480p15, m=720p30, h=1080p60
) {

    fun isAvailable(): Boolean = try {
        val proc = ProcessBuilder(manimExecutable, "--version")
            .redirectErrorStream(true).start()
        proc.waitFor() == 0
    } catch (_: Exception) { false }

    /**
     * Renders [sceneName] from [scriptFile] and writes the video to [outputFile].
     * [durationHint] is used only when the scene defines its own timing; if null,
     * the scene controls its own duration via self.wait() calls.
     */
    fun render(scriptFile: File, sceneName: String, outputFile: File) {
        if (!isAvailable()) throw ManimException("Manim not found at: $manimExecutable")
        if (!scriptFile.exists()) throw ManimException("Script not found: ${scriptFile.absolutePath}")

        val mediaDir = outputFile.parentFile.resolve(".manim-media")
        mediaDir.mkdirs()

        val proc = ProcessBuilder(
            manimExecutable,
            "-q$quality",
            "--format=mp4",
            "--media_dir", mediaDir.absolutePath,
            "--disable_caching",
            scriptFile.absolutePath,
            sceneName
        ).redirectErrorStream(true).start()

        val output = proc.inputStream.bufferedReader().readText()
        val exitCode = proc.waitFor()

        if (exitCode != 0) {
            throw ManimException("Manim render failed (exit $exitCode):\n$output")
        }

        // Find the produced MP4 and move it to outputFile location
        val rendered = mediaDir.walk()
            .filter { it.extension == "mp4" && it.nameWithoutExtension == sceneName }
            .firstOrNull()
            ?: throw ManimException("Manim produced no output for scene '$sceneName'. Log:\n$output")

        // Convert MP4 → WebM so it can be FFmpeg-concatenated with HTML slides
        val convProc = ProcessBuilder(
            "ffmpeg", "-y",
            "-i", rendered.absolutePath,
            "-c:v", "libvpx", "-b:v", "1M",
            "-pix_fmt", "yuv420p",
            "-auto-alt-ref", "0",
            outputFile.absolutePath
        ).redirectErrorStream(true).start()

        val convExit = convProc.waitFor()
        if (convExit != 0) {
            val err = convProc.inputStream.bufferedReader().readText()
            throw ManimException("FFmpeg MP4→WebM conversion failed: $err")
        }

        rendered.delete()
    }

    /** Duration in seconds of the produced video, via ffprobe. */
    fun probeDuration(file: File): Double = try {
        val proc = ProcessBuilder(
            "ffprobe", "-v", "quiet",
            "-show_entries", "format=duration",
            "-of", "csv=p=0",
            file.absolutePath
        ).redirectErrorStream(true).start()
        val d = proc.inputStream.bufferedReader().readText().trim().toDoubleOrNull() ?: 0.0
        proc.waitFor()
        d
    } catch (_: Exception) { 0.0 }
}

class ManimException(message: String) : RuntimeException(message)
