package com.idc.audio_playback_poc

import android.Manifest.permission.RECORD_AUDIO
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
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
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder


class MainActivity : ComponentActivity() {
    @Volatile
    private var started = false

    private var BUFFER_SIZE_RECORDING: Int = 0

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
        val channelConfig = AudioFormat.CHANNEL_IN_STEREO //stereo not tested
        val encoding = AudioFormat.ENCODING_PCM_16BIT

        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(channelConfig)
            .build()

        BUFFER_SIZE_RECORDING = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)

        return AudioRecord.Builder()
            .setAudioFormat(format)
            .setAudioPlaybackCaptureConfig(config)
            .setBufferSizeInBytes(BUFFER_SIZE_RECORDING * 4)
            .build()
    }


    fun startAudioRecording(mediaProjection: MediaProjection) {
        audioRecord = setupAudioRecord(mediaProjection)

        val pcmFile = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toString(),
            "MyRecordPCM.pcm"
        )

        if (audioRecord.state == STATE_INITIALIZED) {
            audioRecord.startRecording()
        } else {
            Log.e("AudioRecord is not initialized ", audioRecord.state.toString())
            return
        }

        Thread {
            writeAudioDataToFile(pcmFile)
            // Cleanup (on stop):
            audioRecord.stop()
            audioRecord.release()
        }.start()
    }

    private fun writeAudioDataToFile(pcmFile: File) {
        // assign size so that bytes are read in in chunks inferior to AudioRecord internal buffer size
        val data = ByteArray(BUFFER_SIZE_RECORDING / 2)
        var outputStream: FileOutputStream? = null
        try {
            outputStream = FileOutputStream(pcmFile)
        } catch (e: FileNotFoundException) {
            return
        }
        while (started) {
            val read = audioRecord.read(data, 0, data.size)
            try {
                outputStream.write(data, 0, read)
                // clean up file writing operations
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        try {
            outputStream.flush()
            outputStream.close()
        } catch (e: IOException) {
            Log.e("TAG", "exception while closing output stream $e")
            e.printStackTrace()
        }
        val wavFile = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toString(),
            "MyRecordWav.wav"
        )
        rawToWave(pcmFile, wavFile, 44100)
    }

    @Throws(IOException::class)
    private fun rawToWave(rawFile: File, waveFile: File, sampleRate: Int) {
        val rawData = ByteArray(rawFile.length().toInt())
        var input: DataInputStream? = null
        try {
            input = DataInputStream(FileInputStream(rawFile))
            input.read(rawData)
        } finally {
            input?.close()
        }
        var output: DataOutputStream? = null
        try {
            output = DataOutputStream(FileOutputStream(waveFile))
            // WAVE header
            // see http://ccrma.stanford.edu/courses/422/projects/WaveFormat/
            writeString(output, "RIFF") // chunk id
            writeInt(output, 36 + rawData.size) // chunk size
            writeString(output, "WAVE") // format
            writeString(output, "fmt ") // subchunk 1 id
            writeInt(output, 16) // subchunk 1 size
            writeShort(output, 1.toShort()) // audio format (1 = PCM)
            writeShort(output, 1.toShort()) // number of channels
            writeInt(output, sampleRate) // sample rate
            writeInt(output, sampleRate * 2) // byte rate     --------- Here changen maybe
            writeShort(output, 2.toShort()) // block align
            writeShort(output, 16.toShort()) // bits per sample
            writeString(output, "data") // subchunk 2 id
            writeInt(output, rawData.size) // subchunk 2 size
            // Audio data (conversion big endian -> little endian)
            val shorts = ShortArray(rawData.size / 2)
            ByteBuffer.wrap(rawData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
            val bytes: ByteBuffer = ByteBuffer.allocate(shorts.size * 2)
            for (s in shorts) {
                bytes.putShort(s)
            }
            output.write(fullyReadFileToBytes(rawFile))
        } finally {
            output?.close()
        }
    }

    @Throws(IOException::class)
    fun fullyReadFileToBytes(f: File): ByteArray? {
        val size = f.length().toInt()
        val bytes = ByteArray(size)
        val tmpBuff = ByteArray(size)
        val fis = FileInputStream(f)
        try {
            var read = fis.read(bytes, 0, size)
            if (read < size) {
                var remain = size - read
                while (remain > 0) {
                    read = fis.read(tmpBuff, 0, remain)
                    System.arraycopy(tmpBuff, 0, bytes, size - remain, read)
                    remain -= read
                }
            }
        } catch (e: IOException) {
            throw e
        } finally {
            fis.close()
        }
        return bytes
    }

    @Throws(IOException::class)
    private fun writeInt(output: DataOutputStream, value: Int) {
        output.write(value shr 0)
        output.write(value shr 8)
        output.write(value shr 16)
        output.write(value shr 24)
    }

    @Throws(IOException::class)
    private fun writeShort(output: DataOutputStream, value: Short) {
        var v = value.toInt()
        output.write(v shr 0)
        output.write(v shr 8)
    }

    @Throws(IOException::class)
    private fun writeString(output: DataOutputStream, value: String) {
        for (i in 0 until value.length) {
            output.write(value[i].code)     //  ------ Here changen maybe
        }
    }
}