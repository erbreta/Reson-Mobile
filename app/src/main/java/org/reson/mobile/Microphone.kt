package org.reson.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import java.util.concurrent.Executor

internal class Microphone private constructor(private val recorder: AudioRecord) : CaptureInput {
    @Volatile private var silenced = false
    private val callback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            silenced = configs.any { it.clientAudioSessionId == recorder.audioSessionId && it.isClientSilenced }
        }
    }
    override val sampleRate: Int get() = recorder.sampleRate
    override val description: String get() {
        val source = when (recorder.audioSource) {
            MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED (device-advertised)"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION fallback; processing may apply"
            else -> "MIC fallback; processing may apply"
        }
        val deviceRate = recorder.activeRecordingConfiguration?.format?.sampleRate
        val hardware = if (deviceRate != null) "Device reports $deviceRate Hz; Android may convert internally."
            else "Physical device rate unavailable."
        return "Capture: $sampleRate Hz · mono · PCM 16-bit\n$source\n$hardware"
    }

    override fun read(samples: ShortArray): Int {
        check(!silenced && recorder.activeRecordingConfiguration?.isClientSilenced != true) {
            "Microphone interrupted or silenced by Android; recording discarded"
        }
        val count = recorder.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
        check(!silenced) { "Microphone interrupted; recording discarded" }
        return count
    }

    override fun stop() {
        if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
    }

    override fun close() {
        try {
            recorder.unregisterAudioRecordingCallback(callback)
            stop()
        } finally { recorder.release() }
    }

    companion object {
        @SuppressLint("MissingPermission") // Activity requests RECORD_AUDIO before opening a session.
        fun open(context: Context): Microphone {
            val manager = checkNotNull(context.getSystemService(AudioManager::class.java)) { "Audio service unavailable" }
            val sources = buildList {
                if (manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true") {
                    add(MediaRecorder.AudioSource.UNPROCESSED)
                }
                add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                add(MediaRecorder.AudioSource.MIC)
            }
            var lastFailure: Exception? = null
            for (source in sources) for (rate in listOf(48000, 44100)) {
                var record: AudioRecord? = null
                try {
                    val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    if (minimum <= 0) continue
                    record = AudioRecord.Builder().setAudioSource(source)
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setBufferSizeInBytes(maxOf(minimum * 2, rate / 5 * 2)).build()
                    check(record.state == AudioRecord.STATE_INITIALIZED && record.sampleRate in listOf(48000, 44100) &&
                        record.channelCount == 1 && record.audioFormat == AudioFormat.ENCODING_PCM_16BIT) {
                        "Unsupported microphone configuration"
                    }
                    val microphone = Microphone(record)
                    record.startRecording()
                    check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone unavailable" }
                    record.registerAudioRecordingCallback(Executor { it.run() }, microphone.callback)
                    return microphone
                } catch (e: SecurityException) {
                    record?.release()
                    throw e
                } catch (e: Exception) {
                    record?.release()
                    lastFailure = e
                }
            }
            throw IllegalStateException("No supported 48 kHz / 44.1 kHz mono microphone configuration", lastFailure)
        }
    }
}
