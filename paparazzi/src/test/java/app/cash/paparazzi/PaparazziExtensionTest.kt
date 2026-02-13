/*
 * Copyright (C) 2026 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package app.cash.paparazzi

import android.graphics.Color
import android.view.View
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Path
import javax.imageio.ImageIO

class PaparazziExtensionTest {
  companion object {
    private var oldSnapshotDir: String? = null

    @JvmStatic
    @BeforeAll
    fun setUpClass(@TempDir tempDir: Path) {
      oldSnapshotDir = System.getProperty("paparazzi.snapshot.dir")
      System.setProperty(
        "paparazzi.snapshot.dir",
        tempDir.resolve("snapshots").toString()
      )
    }

    @JvmStatic
    @AfterAll
    fun tearDownClass() {
      if (oldSnapshotDir == null) {
        System.clearProperty("paparazzi.snapshot.dir")
      } else {
        System.setProperty("paparazzi.snapshot.dir", oldSnapshotDir!!)
      }
    }
  }

  private val handler = TestSnapshotHandler()

  @JvmField
  @RegisterExtension
  val paparazzi = PaparazziExtension { Paparazzi(snapshotHandler = handler) }

  @Test
  fun snapshotRecordsTestName() {
    val view = View(paparazzi.context)
    paparazzi.snapshot(view)

    assertThat(handler.snapshots).hasSize(1)
    assertThat(handler.snapshots[0].testName.className)
      .isEqualTo("PaparazziExtensionTest")
    assertThat(handler.snapshots[0].testName.methodName)
      .isEqualTo("snapshotRecordsTestName")
  }

  @Test
  fun snapshotProducesOutputFile() {
    val view = View(paparazzi.context)
    paparazzi.snapshot(view)

    assertThat(handler.outputFiles).hasSize(1)
    assertThat(handler.outputFiles[0].exists()).isTrue()
  }

  @Test
  fun snapshotWithNameSetsSnapshotName() {
    val view = View(paparazzi.context)
    paparazzi.snapshot(view, name = "custom-name")

    assertThat(handler.snapshots).hasSize(1)
    assertThat(handler.snapshots[0].name).isEqualTo("custom-name")
  }

  @Test
  fun gifRecordsMultipleFrames() {
    val view = View(paparazzi.context)
    paparazzi.gif(view, start = 0L, end = 500L, fps = 4)

    assertThat(handler.snapshots).hasSize(1)
    assertThat(handler.outputFiles).hasSize(1)
    // gif produces multiple frames to a single handler
    assertThat(handler.frameCounts[0]).isGreaterThan(1)
  }

  @Test
  fun multipleSnapshotsProduceMultipleOutputFiles() {
    val view1 = View(paparazzi.context)
    val view2 = View(paparazzi.context).apply {
      setBackgroundColor(Color.RED)
    }
    paparazzi.snapshot(view1, name = "first")
    paparazzi.snapshot(view2, name = "second")

    assertThat(handler.snapshots).hasSize(2)
    assertThat(handler.snapshots[0].name).isEqualTo("first")
    assertThat(handler.snapshots[1].name).isEqualTo("second")
    assertThat(handler.outputFiles).hasSize(2)
  }

  @Test
  fun updateConfigRecordsSnapshot() {
    paparazzi.updateConfig(theme = "android:Theme.Material.Light") {
      val view = View(paparazzi.context)
      snapshot(view, name = "light-theme")
    }

    assertThat(handler.snapshots).hasSize(1)
    assertThat(handler.snapshots[0].name).isEqualTo("light-theme")
    assertThat(handler.outputFiles).hasSize(1)
  }

  @Test
  fun updateConfigMultipleConfigsProducesMultipleSnapshots() {
    val view = View(paparazzi.context)
    paparazzi.snapshot(view, name = "default")

    paparazzi.updateConfig(theme = "android:Theme.Material.Light") {
      snapshot(view, name = "light")
    }

    paparazzi.updateConfig(theme = "android:Theme.Material") {
      snapshot(view, name = "material")
    }

    assertThat(handler.snapshots).hasSize(3)
    assertThat(handler.snapshots.map { it.name })
      .containsExactly("default", "light", "material")
      .inOrder()
    assertThat(handler.outputFiles).hasSize(3)
  }

  @Test
  fun contextResourcesAndLayoutInflaterAreAccessible() {
    assertThat(paparazzi.context).isNotNull()
    assertThat(paparazzi.resources).isNotNull()
    assertThat(paparazzi.layoutInflater).isNotNull()
  }

  /**
   * A [SnapshotHandler] that records all snapshots and their output files for test verification.
   */
  private class TestSnapshotHandler : SnapshotHandler {
    val snapshots = mutableListOf<Snapshot>()
    val outputFiles = mutableListOf<File>()
    val frameCounts = mutableListOf<Int>()

    override fun newFrameHandler(snapshot: Snapshot, frameCount: Int, fps: Int): SnapshotHandler.FrameHandler {
      snapshots += snapshot

      return object : SnapshotHandler.FrameHandler {
        private val tmpFile = File.createTempFile("test-snapshot", ".png")
        private var frames = 0

        override var outputFile: File? = null
          private set

        override fun handle(image: BufferedImage) {
          frames++
          ImageIO.write(image, "PNG", tmpFile)
        }

        override fun close() {
          if (frames > 0) {
            outputFile = tmpFile
            outputFiles += tmpFile
          }
          frameCounts += frames
        }
      }
    }

    override fun close() {}
  }
}
