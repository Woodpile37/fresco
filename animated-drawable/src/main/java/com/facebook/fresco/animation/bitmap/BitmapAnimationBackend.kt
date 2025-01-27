/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.animation.bitmap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import androidx.annotation.IntDef
import androidx.annotation.IntRange
import com.facebook.common.logging.FLog
import com.facebook.common.references.CloseableReference
import com.facebook.fresco.animation.backend.AnimationBackend
import com.facebook.fresco.animation.backend.AnimationBackendDelegateWithInactivityCheck.InactivityListener
import com.facebook.fresco.animation.backend.AnimationInformation
import com.facebook.fresco.animation.bitmap.preparation.BitmapFramePreparationStrategy
import com.facebook.fresco.animation.bitmap.preparation.BitmapFramePreparer
import com.facebook.imagepipeline.bitmaps.PlatformBitmapFactory
import java.lang.RuntimeException

/**
 * Bitmap animation backend that renders bitmap frames.
 *
 * The given [BitmapFrameCache] is used to cache frames and create new bitmaps.
 * [AnimationInformation] defines the main animation parameters, like frame and loop count.
 * [BitmapFrameRenderer] is used to render frames to the bitmaps aquired from the [BitmapFrameCache]
 * .
 */
class BitmapAnimationBackend(
    private val platformBitmapFactory: PlatformBitmapFactory,
    private val bitmapFrameCache: BitmapFrameCache,
    private val animationInformation: AnimationInformation,
    private val bitmapFrameRenderer: BitmapFrameRenderer,
    private val bitmapFramePreparationStrategy: BitmapFramePreparationStrategy?,
    private val bitmapFramePreparer: BitmapFramePreparer?
) : AnimationBackend, InactivityListener {

  interface FrameListener {
    /**
     * Called when the backend started drawing the given frame.
     *
     * @param backend the backend
     * @param frameNumber the frame number to be drawn
     */
    fun onDrawFrameStart(backend: BitmapAnimationBackend, frameNumber: Int)

    /**
     * Called when the given frame has been drawn.
     *
     * @param backend the backend
     * @param frameNumber the frame number that has been drawn
     * @param frameType the [FrameType] that has been drawn
     */
    fun onFrameDrawn(backend: BitmapAnimationBackend, frameNumber: Int, @FrameType frameType: Int)

    /**
     * Called when no bitmap could be drawn by the backend for the given frame number.
     *
     * @param backend the backend
     * @param frameNumber the frame number that could not be drawn
     */
    fun onFrameDropped(backend: BitmapAnimationBackend, frameNumber: Int)
  }

  /** Frame type that has been drawn. Can be used for logging. */
  @Retention(AnnotationRetention.SOURCE)
  @IntDef(
      FRAME_TYPE_UNKNOWN,
      FRAME_TYPE_CACHED,
      FRAME_TYPE_REUSED,
      FRAME_TYPE_CREATED,
      FRAME_TYPE_FALLBACK)
  annotation class FrameType

  var bitmapConfig = Bitmap.Config.ARGB_8888

  private val paint: Paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
  private var bounds: Rect? = null
  private var bitmapWidth = 0
  private var bitmapHeight = 0

  private var frameListener: FrameListener? = null

  init {
    updateBitmapDimensions()
  }

  fun setFrameListener(frameListener: FrameListener?) {
    this.frameListener = frameListener
  }

  override fun getFrameCount(): Int = animationInformation.frameCount

  override fun getFrameDurationMs(frameNumber: Int): Int =
      animationInformation.getFrameDurationMs(frameNumber)

  override fun getLoopCount(): Int = animationInformation.loopCount

  override fun drawFrame(parent: Drawable, canvas: Canvas, frameNumber: Int): Boolean {
    frameListener?.onDrawFrameStart(this, frameNumber)
    val drawn = drawFrameOrFallback(canvas, frameNumber, FRAME_TYPE_CACHED)

    // We could not draw anything
    if (!drawn) {
      frameListener?.onFrameDropped(this, frameNumber)
    }

    // Prepare next frames
    if (bitmapFramePreparer != null) {
      bitmapFramePreparationStrategy?.prepareFrames(
          bitmapFramePreparer, bitmapFrameCache, this, frameNumber)
    }
    return drawn
  }

  private fun drawFrameOrFallback(
      canvas: Canvas,
      frameNumber: Int,
      @FrameType frameType: Int
  ): Boolean {
    var bitmapReference: CloseableReference<Bitmap>? = null
    var drawn = false
    var nextFrameType = FRAME_TYPE_UNKNOWN
    try {
      when (frameType) {
        FRAME_TYPE_CACHED -> {
          bitmapReference = bitmapFrameCache.getCachedFrame(frameNumber)
          drawn = drawBitmapAndCache(frameNumber, bitmapReference, canvas, FRAME_TYPE_CACHED)
          nextFrameType = FRAME_TYPE_REUSED
        }
        FRAME_TYPE_REUSED -> {
          bitmapReference =
              bitmapFrameCache.getBitmapToReuseForFrame(frameNumber, bitmapWidth, bitmapHeight)
          // Try to render the frame and draw on the canvas immediately after
          drawn =
              (renderFrameInBitmap(frameNumber, bitmapReference) &&
                  drawBitmapAndCache(frameNumber, bitmapReference, canvas, FRAME_TYPE_REUSED))
          nextFrameType = FRAME_TYPE_CREATED
        }
        FRAME_TYPE_CREATED -> {
          bitmapReference =
              try {
                platformBitmapFactory.createBitmap(bitmapWidth, bitmapHeight, bitmapConfig)
              } catch (e: RuntimeException) {
                // Failed to create the bitmap for the frame, return and report that we could not
                // draw the frame.
                FLog.w(TAG, "Failed to create frame bitmap", e)
                return false
              }
          // Try to render the frame and draw on the canvas immediately after
          drawn =
              (renderFrameInBitmap(frameNumber, bitmapReference) &&
                  drawBitmapAndCache(frameNumber, bitmapReference, canvas, FRAME_TYPE_CREATED))
          nextFrameType = FRAME_TYPE_FALLBACK
        }
        FRAME_TYPE_FALLBACK -> {
          bitmapReference = bitmapFrameCache.getFallbackFrame(frameNumber)
          drawn = drawBitmapAndCache(frameNumber, bitmapReference, canvas, FRAME_TYPE_FALLBACK)
        }
        else -> return false
      }
    } finally {
      CloseableReference.closeSafely(bitmapReference)
    }
    return if (drawn || nextFrameType == FRAME_TYPE_UNKNOWN) {
      drawn
    } else {
      drawFrameOrFallback(canvas, frameNumber, nextFrameType)
    }
  }

  override fun setAlpha(@IntRange(from = 0, to = 255) alpha: Int) {
    paint.alpha = alpha
  }

  override fun setColorFilter(colorFilter: ColorFilter?) {
    paint.colorFilter = colorFilter
  }

  override fun setBounds(bounds: Rect?) {
    this.bounds = bounds
    bitmapFrameRenderer.setBounds(bounds)
    updateBitmapDimensions()
  }

  override fun getIntrinsicWidth(): Int = bitmapWidth

  override fun getIntrinsicHeight(): Int = bitmapHeight

  override fun getSizeInBytes(): Int = bitmapFrameCache.sizeInBytes

  override fun clear() {
    bitmapFrameCache.clear()
  }

  override fun onInactive() {
    clear()
  }

  private fun updateBitmapDimensions() {
    // Calculate the correct bitmap dimensions
    bitmapWidth = bitmapFrameRenderer.intrinsicWidth
    if (bitmapWidth == AnimationBackend.INTRINSIC_DIMENSION_UNSET) {
      bitmapWidth = bounds?.width() ?: AnimationBackend.INTRINSIC_DIMENSION_UNSET
    }
    bitmapHeight = bitmapFrameRenderer.intrinsicHeight
    if (bitmapHeight == AnimationBackend.INTRINSIC_DIMENSION_UNSET) {
      bitmapHeight = bounds?.height() ?: AnimationBackend.INTRINSIC_DIMENSION_UNSET
    }
  }

  /**
   * Try to render the frame to the given target bitmap. If the rendering fails, the target bitmap
   * reference will be closed and false is returned. If rendering succeeds, the target bitmap
   * reference can be drawn and has to be manually closed after drawing has been completed.
   *
   * @param frameNumber the frame number to render
   * @param targetBitmap the target bitmap
   * @return true if rendering successful
   */
  private fun renderFrameInBitmap(
      frameNumber: Int,
      targetBitmap: CloseableReference<Bitmap>?
  ): Boolean {
    if (targetBitmap == null || !targetBitmap.isValid) {
      return false
    }
    // Render the image
    val frameRendered = bitmapFrameRenderer.renderFrame(frameNumber, targetBitmap.get())
    if (!frameRendered) {
      CloseableReference.closeSafely(targetBitmap)
    }
    return frameRendered
  }

  /**
   * Helper method that draws the given bitmap on the canvas respecting the bounds (if set).
   *
   * If rendering was successful, it notifies the cache that the frame has been rendered with the
   * given bitmap. In addition, it will notify the [FrameListener] if set.
   *
   * @param frameNumber the current frame number passed to the cache
   * @param bitmapReference the bitmap to draw
   * @param canvas the canvas to draw an
   * @param frameType the [FrameType] to be rendered
   * @return true if the bitmap has been drawn
   */
  private fun drawBitmapAndCache(
      frameNumber: Int,
      bitmapReference: CloseableReference<Bitmap>?,
      canvas: Canvas,
      @FrameType frameType: Int
  ): Boolean {
    if (bitmapReference == null || !CloseableReference.isValid(bitmapReference)) {
      return false
    }
    val currentBounds = bounds
    if (currentBounds == null) {
      canvas.drawBitmap(bitmapReference.get(), 0f, 0f, paint)
    } else {
      canvas.drawBitmap(bitmapReference.get(), null, currentBounds, paint)
    }

    // Notify the cache that a frame has been rendered.
    // We should not cache fallback frames since they do not represent the actual frame.
    if (frameType != FRAME_TYPE_FALLBACK) {
      bitmapFrameCache.onFrameRendered(frameNumber, bitmapReference, frameType)
    }
    frameListener?.onFrameDrawn(this, frameNumber, frameType)
    return true
  }

  companion object {
    const val FRAME_TYPE_UNKNOWN = -1
    const val FRAME_TYPE_CACHED = 0
    const val FRAME_TYPE_REUSED = 1
    const val FRAME_TYPE_CREATED = 2
    const val FRAME_TYPE_FALLBACK = 3
    private val TAG = BitmapAnimationBackend::class.java
  }
}
