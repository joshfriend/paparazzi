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

import android.content.Context
import android.content.res.Resources
import android.view.LayoutInflater
import android.view.View
import androidx.annotation.LayoutRes
import androidx.compose.runtime.Composable
import com.android.ide.common.rendering.api.SessionParams.RenderingMode
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.MediaType
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger
import java.nio.file.Files as NioFiles

/**
 * A JUnit 5 extension that wraps [Paparazzi] and automatically attaches snapshot images
 * to Gradle test reports using the [ExtensionContext.publishFile] API (Gradle 9.4+ / JUnit 5.12+).
 *
 * This attaches the actual output file produced by the snapshot handler — a single PNG for
 * snapshots and a full animated PNG for gifs.
 *
 * The [Paparazzi] instance is created fresh for each test via the [factory] lambda, ensuring
 * clean state and preventing accidental direct access to the underlying [Paparazzi] instance.
 * Use [updateConfig] to change device config, theme, or rendering mode within a test.
 *
 * Usage:
 * ```
 * class MySnapshotTest {
 *   @JvmField
 *   @RegisterExtension
 *   val paparazzi = PaparazziExtension {
 *     Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)
 *   }
 *
 *   @Test
 *   fun test() {
 *     paparazzi.snapshot { MyComposable() }
 *   }
 * }
 * ```
 *
 * Snapshot images are published to the Gradle HTML test report after each test. This requires
 * JUnit Jupiter 5.12+ and Gradle 9.4+ for the images to appear in the test report.
 */
public class PaparazziExtension(
  private val factory: () -> Paparazzi = { Paparazzi() }
) : BeforeEachCallback, AfterEachCallback {
  private val logger = Logger.getLogger(PaparazziExtension::class.java.name)
  private val capturedFiles = mutableListOf<File>()
  private lateinit var paparazzi: Paparazzi

  public val layoutInflater: LayoutInflater
    get() = paparazzi.layoutInflater

  public val resources: Resources
    get() = paparazzi.resources

  public val context: Context
    get() = paparazzi.context

  public fun <V : View> inflate(@LayoutRes layoutId: Int): V = paparazzi.inflate(layoutId)

  public fun snapshot(name: String? = null, composable: @Composable () -> Unit) {
    paparazzi.snapshot(name, composable)
  }

  @JvmOverloads
  public fun snapshot(view: View, name: String? = null, offsetMillis: Long = 0L) {
    paparazzi.snapshot(view, name, offsetMillis)
  }

  @JvmOverloads
  public fun gif(view: View, name: String? = null, start: Long = 0L, end: Long = 500L, fps: Int = 30) {
    paparazzi.gif(view, name, start, end, fps)
  }

  /**
   * Updates the rendering configuration and executes [block] with the new config in effect.
   *
   * This permanently changes the device config, theme, or rendering mode for the remainder of
   * the test. The block receives this [PaparazziExtension] as a receiver, so [snapshot], [gif],
   * and [inflate] can be called directly inside it.
   *
   * ```
   * paparazzi.updateConfig(theme = "android:Theme.Material.Light") {
   *   val launch = inflate<LinearLayout>(R.layout.launch)
   *   snapshot(view = launch, name = "light")
   * }
   *
   * paparazzi.updateConfig(deviceConfig = DeviceConfig.NEXUS_5.copy(orientation = LANDSCAPE)) {
   *   snapshot(view = launch, name = "landscape")
   * }
   * ```
   */
  public fun updateConfig(
    deviceConfig: DeviceConfig? = null,
    theme: String? = null,
    renderingMode: RenderingMode? = null,
    block: PaparazziExtension.() -> Unit
  ) {
    paparazzi.unsafeUpdateConfig(deviceConfig, theme, renderingMode)
    block()
  }

  override fun beforeEach(context: ExtensionContext) {
    paparazzi = factory()
    paparazzi.onSnapshotFile = { file -> capturedFiles += file }
    paparazzi.setup(context.toTestName())
  }

  override fun afterEach(context: ExtensionContext) {
    try {
      publishCapturedFiles(context)
    } finally {
      capturedFiles.clear()
      if (::paparazzi.isInitialized) {
        paparazzi.onSnapshotFile = null
        paparazzi.teardown()
      }
    }
  }

  private fun publishCapturedFiles(context: ExtensionContext) {
    for (file in capturedFiles) {
      try {
        context.publishFile(file.name, MediaType.IMAGE_PNG) { destPath ->
          NioFiles.copy(file.toPath(), destPath)
        }
      } catch (t: Throwable) {
        logger.log(Level.WARNING, "Failed to attach snapshot to test report. This requires JUnit Jupiter 5.12+ and Gradle 9.4+.", t)
      }
    }
  }

  private fun ExtensionContext.toTestName(): TestName {
    return TestName(
      packageName = requiredTestClass.`package`?.name.orEmpty(),
      className = requiredTestClass.simpleName,
      methodName = invocationAwareMethodName(requiredTestMethod.name, uniqueId)
    )
  }

  internal companion object {
    internal fun invocationAwareMethodName(methodName: String, uniqueId: String): String {
      val invocationSuffix = parseInvocationSuffix(uniqueId) ?: return methodName
      return "${methodName}_$invocationSuffix"
    }

    private fun parseInvocationSuffix(uniqueId: String): String? {
      val segment = uniqueId.substringAfterLast('/')
      if (!segment.startsWith('[') || !segment.endsWith(']')) return null
      val body = segment.substring(1, segment.length - 1)
      val type = body.substringBefore(':')
      if (type !in setOf("test-template-invocation", "repetition", "dynamic-test")) return null
      val rawValue = body.substringAfter(':', missingDelimiterValue = "")
      val sanitizedValue = rawValue
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .trim('_')
        .ifEmpty { "invocation" }
      return "${type}_$sanitizedValue"
    }
  }
}
