package icu.azim.hyyap;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

import de.maxhenkel.opus4j.OpusEncoder;
import de.maxhenkel.opus4j.UnknownPlatformException;
import icu.azim.hyyap.dectalk.DectalkThread;
import icu.azim.hyyap.util.DependencyLoader;

public class HyYapPlugin extends JavaPlugin {
    public static final int SAMPLERATE = 48000;
    public static final int FRAME_SIZE = 960;

    
    private static final ScheduledExecutorService SENDER_SCHEDULER = Executors.newSingleThreadScheduledExecutor();
    private static final ScheduledExecutorService TTS_SCHEDULER = Executors.newSingleThreadScheduledExecutor();
    
    private ScheduledFuture<?> senderFuture = null; 
    private BroadcastThread senderThread;
    public BroadcastThread getSenderThread() {
        return senderThread;
    }
    //FIXME i dont want to expose threads directly and should instead wrap it into proper api
    private ScheduledFuture<?> dectalkFuture = null;
    private DectalkThread dectalkThread;
    public DectalkThread getDectalk() {
        return dectalkThread;
    }
    
    private static HyYapPlugin instance;
    public static HyYapPlugin getInstance() {
        return instance;
    }
    
    public HyYapPlugin(JavaPluginInit init) {
        super(init);
        DependencyLoader.load(this);
        instance = this;
    }
    
    @Override
    protected void setup() {
        senderThread = new BroadcastThread(); 
        senderFuture = SENDER_SCHEDULER.scheduleAtFixedRate(senderThread, 0, 20, TimeUnit.MILLISECONDS); //every 20 ms

        dectalkThread = new DectalkThread();
        dectalkFuture = TTS_SCHEDULER.scheduleWithFixedDelay(dectalkThread, 0, 200, TimeUnit.MILLISECONDS); //5 times per second
        
    }
    
    @Override
    protected void shutdown() {
        super.shutdown();
        if(dectalkFuture != null) dectalkFuture.cancel(false); 
        if(senderFuture != null) senderFuture.cancel(true);
    }
    
    public static short[] generateBeep(double freqHz, double amplitude) { //i will keep it around for now, maybe will reuse for something later
        short[] samples = new short[FRAME_SIZE];
        double twoPiF = 2.0 * Math.PI * freqHz;
        for (int i = 0; i < FRAME_SIZE; i++) {
            double t = i / (double) 48000;
            double s = Math.sin(twoPiF * t);
            samples[i] = (short) Math.round(s * amplitude * Short.MAX_VALUE);
        }
        return samples;
    }
    
    public static List<byte[]> generateOpusFrames(short[] samples, int sourceSampleRate) throws IOException, UnknownPlatformException{
        //upsample to 48000
        int outputLength = (int) Math.round(samples.length * (double) SAMPLERATE / sourceSampleRate);
        short[] output = new short[outputLength];

        double step = (double) sourceSampleRate / SAMPLERATE;

        for (int i = 0; i < output.length; i++) {
            double srcPos = i * step;
            int leftIndex = (int) Math.floor(srcPos);
            double frac = srcPos - leftIndex;

            if (leftIndex >= samples.length - 1) {
                output[i] = samples[samples.length - 1];
            } else {
                int s1 = samples[leftIndex];
                int s2 = samples[leftIndex + 1];
                double sample = s1 + frac * (s2 - s1);
                output[i] = (short) Math.round(sample);
            }
        }
        
        //split into 20ms frames and encode with opus
        List<byte[]> frames = new ArrayList<byte[]>();
        try (OpusEncoder encoder = new OpusEncoder(SAMPLERATE, 1, OpusEncoder.Application.VOIP)) {
            encoder.resetState(); // only reset once
            encoder.setMaxPayloadSize(1500);

            for (int i = 0; i < output.length; i += FRAME_SIZE) {
                int len = Math.min(FRAME_SIZE, output.length - i);

                short[] frame = new short[FRAME_SIZE];
                System.arraycopy(output, i, frame, 0, len);
                byte[] result = encoder.encode(frame);
                frames.add(result);
            }
        }
        
        return frames;
    }

}
