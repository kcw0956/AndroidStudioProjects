package com.example.lyrics_mobile;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class AudioSampleRecorder {
    public static final int SAMPLE_RATE = 16000;
    @SuppressLint("MissingPermission") // Caller requests RECORD_AUDIO before entering.
    public byte[] record(OperationScope scope) throws IOException {
        int size = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (size <= 0) throw new IOException("이 기기에서 녹음을 시작하지 못했어요.");
        AudioRecord record = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(size, 4096));
        try {
            if (record.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("마이크를 사용할 수 없어요.");
            record.startRecording();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[2048];
            int target = SAMPLE_RATE * 2 * 10;
            while (out.size() < target) {
                scope.check();
                int n = record.read(buffer, 0, Math.min(buffer.length, target - out.size()));
                if (n <= 0) throw new IOException("마이크 녹음이 중단되었어요.");
                out.write(buffer, 0, n);
            }
            scope.check();
            return wav(out.toByteArray());
        } finally {
            try { if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) record.stop(); }
            finally { record.release(); }
        }
    }
    public static byte[] wav(byte[] pcm) {
        ByteBuffer out = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        out.put(new byte[]{'R','I','F','F'}).putInt(36 + pcm.length).put(new byte[]{'W','A','V','E','f','m','t',' '});
        out.putInt(16).putShort((short) 1).putShort((short) 1).putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2);
        out.putShort((short) 2).putShort((short) 16).put(new byte[]{'d','a','t','a'}).putInt(pcm.length).put(pcm);
        return out.array();
    }
}
