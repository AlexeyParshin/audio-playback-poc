package com.idc.audio_playback_poc

import android.Manifest.permission.RECORD_AUDIO
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.media.*
import android.media.AudioRecord.STATE_INITIALIZED
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream


class MainActivity : ComponentActivity() {
    @Volatile
    private var started = false
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var mediaProjection: MediaProjection
    private lateinit var audioRecord: AudioRecord

    private val projectionPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                mediaProjection = mediaProjectionManager.getMediaProjection(result.resultCode, result.data!!)
                startAudioRecording(mediaProjection)
            } else {
                Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val button = findViewById<Button>(R.id.start)

        if (ContextCompat.checkSelfPermission(this, RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(RECORD_AUDIO), 1234)
        }

        button.setOnClickListener {
            if (started) {
                started = false
                stopService(Intent(this, MediaProjectionService::class.java))
                button.text = "STOPPED"
            } else {
                val serviceIntent = Intent(this, MediaProjectionService::class.java)
                ContextCompat.startForegroundService(this, serviceIntent)
                requestMediaProjection()
                started = true
                button.text = "STARTED"
            }
        }
    }

    fun requestMediaProjection() {
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        projectionPermissionLauncher.launch(captureIntent)
    }

    @SuppressLint("MissingPermission")
    fun setupAudioRecord(mediaProjection: MediaProjection): AudioRecord {
        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT

        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(channelConfig)
            .build()

        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)

        return AudioRecord.Builder()
            .setAudioFormat(format)
            .setAudioPlaybackCaptureConfig(config)
            .setBufferSizeInBytes(bufferSize * 4)
            .build()
    }


    fun startAudioRecording(mediaProjection: MediaProjection) {
        audioRecord = setupAudioRecord(mediaProjection)
        val encoder = setupAacEncoder()
        encoder.start()

        val buffer = ByteArray(2048)

        val outputFile = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).toString(),
            "MyRecord.aac"
        )
        val outputStream = FileOutputStream(outputFile)

        Thread {
            if (audioRecord.state == STATE_INITIALIZED) {
                audioRecord.startRecording()
            } else {
                Log.e("AudioRecord is not initialized ", audioRecord.state.toString())
                return@Thread
            }

            val bufferInfo = MediaCodec.BufferInfo()

            while (started) {
                val inputIndex = encoder.dequeueInputBuffer(10000)
                if (inputIndex >= 0) {
                    val inputBuffer = encoder.getInputBuffer(inputIndex)
                    inputBuffer?.clear()

                    val readBytes = audioRecord.read(buffer, 0, buffer.size)
                    if (readBytes > 0) {
                        inputBuffer?.put(buffer, 0, readBytes)
                        encoder.queueInputBuffer(inputIndex, 0, readBytes, System.nanoTime() / 1000, 0)
                    }
                }

                var outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
                while (outputIndex >= 0) {
                    val outputBuffer = encoder.getOutputBuffer(outputIndex) ?: break
                    val outData = ByteArray(bufferInfo.size)
                    outputBuffer.get(outData)
                    outputBuffer.clear()

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        outputStream.write(outData)
                    }

                    encoder.releaseOutputBuffer(outputIndex, false)
                    outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
                }
            }

            // Cleanup (on stop):
            encoder.stop()
            encoder.release()
            audioRecord.stop()
            audioRecord.release()
            outputStream.close()
        }.start()
    }

    fun setupAacEncoder(): MediaCodec {
        val sampleRate = 44100
        val channelCount = 1

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 64000)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                TODO("Not yet implemented")
            }

            override fun onOutputBufferAvailable(
                codec: MediaCodec,
                index: Int,
                info: MediaCodec.BufferInfo
            ) {
                TODO("Not yet implemented")
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                TODO("Not yet implemented")
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                TODO("Not yet implemented")
            }
        })

        return encoder
    }
}