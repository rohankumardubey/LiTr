/*
 * Copyright 2022 LinkedIn Corporation
 * All Rights Reserved.
 *
 * Licensed under the BSD 2-Clause License (the "License").  See License in the project root for
 * license information.
 */
package com.linkedin.android.litr.render

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.linkedin.android.litr.codec.Encoder
import com.linkedin.android.litr.codec.Frame
import com.linkedin.android.litr.filter.BufferFilter
import com.linkedin.android.litr.utils.ByteBufferPool
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

private const val BYTES_PER_SAMPLE = 2
private const val FRAME_WAIT_TIMEOUT: Long = 0L

private const val TAG = "AudioRenderer"

class AudioRenderer @JvmOverloads constructor(
    private val encoder: Encoder,
    filters: MutableList<BufferFilter>? = null
) : Renderer {

    private val filters: List<BufferFilter> = filters ?: listOf()

    private var sourceMediaFormat: MediaFormat? = null
    private var targetMediaFormat: MediaFormat? = null
    private var targetSampleDurationUs = 0.0
    private var sourceChannelCount = -1
    private var targetChannelCount = -1
    private var samplingRatio = 1.0

    private val bufferPool = ByteBufferPool(true)
    private lateinit var audioProcessor: AudioProcessor

    private var released: AtomicBoolean = AtomicBoolean(false)
    private val renderQueue = LinkedBlockingDeque<Frame>()
    private val renderThread = RenderThread()

    override fun init(outputSurface: Surface?, sourceMediaFormat: MediaFormat?, targetMediaFormat: MediaFormat?) {
        onMediaFormatChanged(sourceMediaFormat, targetMediaFormat)
        released.set(false)
        renderThread.start()
        audioProcessor = AudioProcessorFactory().createAudioProcessor(sourceMediaFormat, targetMediaFormat)
        filters.forEach { it.init(targetMediaFormat) }
    }

    override fun onMediaFormatChanged(sourceMediaFormat: MediaFormat?, targetMediaFormat: MediaFormat?) {
        this.sourceMediaFormat = sourceMediaFormat
        this.targetMediaFormat = targetMediaFormat

        if (targetMediaFormat?.containsKey(MediaFormat.KEY_SAMPLE_RATE) == true) {
            targetSampleDurationUs = 1_000_000.0 / targetMediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            if (sourceMediaFormat?.containsKey(MediaFormat.KEY_SAMPLE_RATE) == true) {
                samplingRatio =
                    targetMediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).toDouble() / sourceMediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            }
        }
        if (sourceMediaFormat?.containsKey(MediaFormat.KEY_CHANNEL_COUNT) == true) {
            sourceChannelCount = sourceMediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        }
        if (targetMediaFormat?.containsKey(MediaFormat.KEY_CHANNEL_COUNT) == true) {
            targetChannelCount = targetMediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        }
    }

    override fun getInputSurface(): Surface? {
        return null
    }

    override fun renderFrame(inputFrame: Frame?, presentationTimeNs: Long) {
        if (!released.get() && inputFrame != null) {
            val sourceSampleCount = inputFrame.bufferInfo.size / (BYTES_PER_SAMPLE * sourceChannelCount)
            val estimatedTargetSampleCount = ceil(sourceSampleCount * samplingRatio).toInt()
            val targetBufferCapacity = estimatedTargetSampleCount * targetChannelCount * BYTES_PER_SAMPLE
            val targetBuffer = bufferPool.get(targetBufferCapacity)

            val processedFrame = Frame(inputFrame.tag, targetBuffer, MediaCodec.BufferInfo())

            audioProcessor.processFrame(inputFrame, processedFrame)
            filters.forEach { it.apply(processedFrame) }

            renderQueue.add(processedFrame)
        }
    }

    override fun release() {
        released.set(true)
        audioProcessor.release()
        bufferPool.clear()
    }

    override fun hasFilters(): Boolean {
        return filters.isNotEmpty()
    }

    private inner class RenderThread : Thread() {
        override fun run() {
            while (!released.get()) {
                renderQueue.peekFirst()?.let { inputFrame ->
                    val tag = encoder.dequeueInputFrame(FRAME_WAIT_TIMEOUT)
                    when {
                        tag >= 0 -> renderFrame(tag, inputFrame)
                        tag == MediaCodec.INFO_TRY_AGAIN_LATER -> {} // do nothing, will try later
                        else -> Log.e(TAG, "Unhandled value $tag when receiving decoded input frame")
                    }
                }
            }
            renderQueue.clear()
        }

        private fun renderFrame(tag: Int, inputFrame: Frame) {
            encoder.getInputFrame(tag)?.let { outputFrame ->
                if (outputFrame.buffer != null && inputFrame.buffer != null) {
                    outputFrame.bufferInfo.offset = 0
                    outputFrame.bufferInfo.flags = inputFrame.bufferInfo.flags
                    outputFrame.bufferInfo.presentationTimeUs =
                        inputFrame.bufferInfo.presentationTimeUs +
                            ((inputFrame.buffer.position() / (targetChannelCount * BYTES_PER_SAMPLE)) * targetSampleDurationUs).toLong()

                    val inputBufferDepleted = if (outputFrame.buffer.limit() >= inputFrame.buffer.remaining()) {
                        // if remaining input bytes fit output buffer, use them all
                        outputFrame.bufferInfo.size = inputFrame.buffer.remaining()
                        true
                    } else {
                        // otherwise, fill the output buffer and clear its EOS flag
                        outputFrame.bufferInfo.size = outputFrame.buffer.limit()
                        outputFrame.bufferInfo.flags = outputFrame.bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv()
                        false
                    }

                    repeat(outputFrame.bufferInfo.size) {
                        outputFrame.buffer.put(inputFrame.buffer.get())
                    }

                    if (inputBufferDepleted) {
                        // all input buffer contents are consumed, remove it from render queue and put it back into buffer pool
                        renderQueue.removeFirst()
                        bufferPool.put(inputFrame.buffer)
                    }

                    encoder.queueInputFrame(outputFrame)
                }
            }
        }
    }
}
