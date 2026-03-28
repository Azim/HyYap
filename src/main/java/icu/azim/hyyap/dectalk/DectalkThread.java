package icu.azim.hyyap.dectalk;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.hypixel.hytale.logger.HytaleLogger;
import de.maxhenkel.opus4j.UnknownPlatformException;
import dev.bytesizedfox.dectalk.TTSNative;
import icu.azim.hyyap.HyYapPlugin;

public class DectalkThread implements Runnable {

    public record SpeechData(String text, CompletableFuture<List<byte[]>> future) {}
    
    private final ConcurrentLinkedQueue<SpeechData> messageQueue = new ConcurrentLinkedQueue<>();
    private boolean initialized;

    @SuppressWarnings("unused")
    @Deprecated
    private String commandPrefix = "[:rate 180][:np]";
    @SuppressWarnings("unused")
    @Deprecated
    private String commandSuffix = "[:phoneme off][:error speak][:mode spell set][:mode spell off][:punct some]";
    //TODO per-plugin config? append reset commands after the text. move pre- and post- commands to plugin config 
    

    /**
     * Synthesizes provided text using DECtalk engine. <br>
     * Encodes result into List of ready-to-use opus frames
     * 
     * @param text Text to synthesize
     * @return CompletableFuture<List<byte[]>> which completes to encoded synthesized text
     */
    public CompletableFuture<List<byte[]>> speakAndEncode(String text){
        if (!TTSNative.isLoaded()) {
            HytaleLogger.get("TTS processing").atSevere().log("TTSNative is not loaded! see server start logs to find out why!");
            return CompletableFuture.failedFuture(
                new IllegalStateException("TTSNative is not loaded")
            );
        }
        if(text.isEmpty()) {
            return CompletableFuture.completedFuture(new ArrayList<byte[]>()); //no input - no frames, no time wasted
        }
        
        CompletableFuture<List<byte[]>> result = new CompletableFuture<>();
        messageQueue.offer(new SpeechData(text, result));
        return result;
    }

    private void ensureInitialized() {
        if (!initialized && TTSNative.isLoaded()) {
            TTSNative.init();
            initialized = true;
        }
    }
    
    @Override
    public void run() {
        if (!TTSNative.isLoaded()) {
            HytaleLogger.get("TTS processing").atSevere().log("TTSNative is not loaded! see server start logs to find out why!");
            return;
        }
        ensureInitialized();

        SpeechData message = messageQueue.poll();
        if (message == null) {
            //no messages in queue
            return;
        }
        
        TTSNative.reset();
        TTSNative.speak(message.text); 
        TTSNative.sync();
        
        int totalSamples = TTSNative.getAvailableSamples();
        if (totalSamples == 0) {
            message.future.complete(new ArrayList<byte[]>());
            return;
        }

        short[] audioData = new short[totalSamples];
        int copied = TTSNative.readSamples(audioData, totalSamples);
        if(copied < 10) {
            HytaleLogger.get("TTS processing").atWarning().log("received very few samples from tts for message: "+message.text);
        }
        List<byte[]> frames;
        try {
            frames = HyYapPlugin.encodeOpusFramesMono(audioData, 11025);
            message.future.complete(frames);
        } catch (IOException | UnknownPlatformException e) {
            e.printStackTrace();
            HytaleLogger.get("TTS processing").atSevere().withCause(e).log("Error encoding speech to opus frames");
            message.future.completeExceptionally(e);
        }
    }

}
