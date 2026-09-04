import java.awt.Color
import java.awt.MultipleGradientPaint
import java.awt.GradientPaint
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Point2D
import java.awt.image.BufferedImage
import java.io.File
import java.util.Properties
import java.util.Random
import javax.imageio.ImageIO
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing is configured only when keystore.properties exists (it is not
// checked in). Without it, `assembleDebug` still works and release builds are
// simply unsigned.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseKeystore = keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "com.peakmotion.ebbfold"
    compileSdk = 35

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            // Keep the building repo's commit SHA out of shipped APKs.
            vcsInfo { include = false }
        }
    }

    flavorDimensions += "wallpaper"
    productFlavors {
        // Each flavor is a separately installable wallpaper app with its own
        // frame set (app/src/<flavor>/assets/frames), name, and thumbnail.
        create("ebbfold") {
            dimension = "wallpaper"
            applicationId = "com.peakmotion.ebbfold"
        }
        create("fluxfold") {
            dimension = "wallpaper"
            applicationId = "com.peakmotion.fluxfold"
        }
        create("fluxfold2") {
            dimension = "wallpaper"
            applicationId = "com.peakmotion.fluxfold2"
        }
    }

    defaultConfig {
        applicationId = "com.peakmotion.ebbfold"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

// ---------------------------------------------------------------------------
// generateFrames — renders the 120-frame "Ebb & Fold" sequence into
// app/src/main/assets/frames/ as frame_000.png … frame_119.png (1040x1080).
//
// Deterministic: a single java.util.Random(42) draws every blob parameter up
// front, so repeated runs produce byte-identical PNGs. Runs headless on the
// build JVM using java.awt / javax.imageio — no Android tooling involved.
// ---------------------------------------------------------------------------
tasks.register("generateFrames") {
    group = "build"
    description = "Renders the 120 deterministic wallpaper frames into src/main/assets/frames/"

    doLast {
        System.setProperty("java.awt.headless", "true")

        val frameW = 1040
        val frameH = 1080
        val frameCount = 120

        val framesDir = layout.projectDirectory.dir("src/main/assets/frames").asFile
        framesDir.mkdirs()
        framesDir.listFiles { f -> f.isFile && f.name.matches(Regex("frame_\\d+\\.png")) }
            ?.forEach { it.delete() }

        val rnd = Random(42)

        // Palette: calm fluid on a deep blue ground (Google "Ebb & Flow" mood).
        val bgTop = Color(0x10, 0x1C, 0x2C)
        val bgBottom = Color(0x1E, 0x3A, 0x5F)
        val palette = listOf(
            Color(0x4C, 0x8D, 0xFF), // soft blue
            Color(0x7F, 0xD1, 0xC8), // seafoam
            Color(0xF2, 0xC9, 0x4C)  // warm sand
        )

        // Draw every random parameter BEFORE the frame loop so frame order
        // never affects the stream — pure functions of t from here on.
        data class Blob(
            val color: Color,
            val alpha: Int,
            val baseX: Double,
            val baseY: Double,
            val radius: Double,
            val ampX: Double,
            val ampY: Double,
            val phaseX: Double,
            val phaseY: Double,
            val cyclesX: Double,
            val cyclesY: Double,
            val pulsePhase: Double
        )

        val blobCount = 3 + rnd.nextInt(3) // 3..5
        val blobs = (0 until blobCount).map { i ->
            Blob(
                color = palette[i % palette.size],
                alpha = 60 + rnd.nextInt(51),                      // 60..110
                baseX = (0.18 + 0.64 * rnd.nextDouble()) * frameW, // keep on-canvas
                baseY = (0.18 + 0.64 * rnd.nextDouble()) * frameH,
                radius = 260.0 + 200.0 * rnd.nextDouble(),         // large, soft
                ampX = 70.0 + 110.0 * rnd.nextDouble(),
                ampY = 70.0 + 110.0 * rnd.nextDouble(),
                phaseX = rnd.nextDouble() * 2.0 * Math.PI,
                phaseY = rnd.nextDouble() * 2.0 * Math.PI,
                cyclesX = 0.5 + 0.75 * rnd.nextDouble(),           // slow drift
                cyclesY = 0.5 + 0.75 * rnd.nextDouble(),
                pulsePhase = rnd.nextDouble() * 2.0 * Math.PI
            )
        }

        for (frame in 0 until frameCount) {
            val t = frame.toDouble() / (frameCount - 1).toDouble() // 0..1
            // Near-linear time so every degree of hinge produces an equal,
            // visible change (the wallpaper is a scrubber, not a player);
            // only the last 10% eases out so frame 119 still lands settled
            // (slope-continuous at the joint, velocity -> 0 at t = 1).
            val settleStart = 0.9
            val a = 1.0 / (1.0 - settleStart * settleStart)
            val tp = if (t <= settleStart) {
                t * 2.0 * a * (1.0 - settleStart)
            } else {
                1.0 - a * (1.0 - t) * (1.0 - t)
            }

            val img = BufferedImage(frameW, frameH, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY)

                // Vertical background gradient.
                g.paint = GradientPaint(0f, 0f, bgTop, 0f, frameH.toFloat(), bgBottom)
                g.fillRect(0, 0, frameW, frameH)

                // Translucent soft-edged blobs drifting on seeded sine paths.
                for (b in blobs) {
                    val x = b.baseX + b.ampX * Math.sin(b.phaseX + 2.0 * Math.PI * b.cyclesX * tp)
                    val y = b.baseY + b.ampY * Math.sin(b.phaseY + 2.0 * Math.PI * b.cyclesY * tp)
                    val r = b.radius * (1.0 + 0.05 * Math.sin(b.pulsePhase + 2.0 * Math.PI * tp))

                    val core = Color(b.color.red, b.color.green, b.color.blue, b.alpha)
                    val mid = Color(b.color.red, b.color.green, b.color.blue, (b.alpha * 0.55).toInt())
                    val edge = Color(b.color.red, b.color.green, b.color.blue, 0)
                    g.paint = RadialGradientPaint(
                        Point2D.Double(x, y),
                        r.toFloat(),
                        floatArrayOf(0.0f, 0.55f, 1.0f),
                        arrayOf(core, mid, edge),
                        MultipleGradientPaint.CycleMethod.NO_CYCLE
                    )
                    g.fill(Ellipse2D.Double(x - r, y - r, r * 2.0, r * 2.0))
                }
            } finally {
                g.dispose()
            }

            val out = File(framesDir, String.format("frame_%03d.png", frame))
            ImageIO.write(img, "png", out)
        }

        logger.lifecycle("generateFrames: wrote $frameCount frames (${frameW}x${frameH}, $blobCount blobs) to ${framesDir}")
    }
}
