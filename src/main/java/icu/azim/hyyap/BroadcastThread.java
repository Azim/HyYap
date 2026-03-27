package icu.azim.hyyap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.Position;
import com.hypixel.hytale.protocol.packets.stream.StreamType;
import com.hypixel.hytale.protocol.packets.voice.RelayedVoiceData;
import com.hypixel.hytale.server.core.modules.voice.VoiceModule;
import com.hypixel.hytale.server.core.modules.voice.VoiceModule.PositionSnapshot;
import com.hypixel.hytale.server.core.universe.PlayerRef;



public class BroadcastThread implements Runnable {

    
    public BroadcastThread() {
    }

    public record BroadcastData(UUID speaker, int entityId, List<byte[]> audioData, Collection<PlayerRef> receivers, Supplier<Boolean> isSubmerged, Supplier<Position> position, int opusTimestamp, boolean isPositionless) {
        BroadcastData advance() {
            if (audioData.isEmpty()) {
                return this;//should never be called - we clean up all entries which have empty audio data
            }
            List<byte[]> copy = new ArrayList<>(audioData);
            copy.removeFirst();
            return new BroadcastData(speaker, entityId, copy, receivers, isSubmerged, position, opusTimestamp + HyYapPlugin.FRAME_SIZE, isPositionless);
        }
    }
    private ConcurrentHashMap<CompletableFuture<Void>, BroadcastData> toBroadcast = new ConcurrentHashMap<>();
    
    private static short sequenceNumber = 0;
    
    @Override
    public void run() {
        if(toBroadcast.isEmpty()) return;
        
        
        for(var entry : toBroadcast.entrySet()) {
            if(entry.getKey().isDone()) continue; //cancelled or completed otherwise
            
            BroadcastData data = entry.getValue();
            byte[] currentFrameData = data.audioData().get(0);
            
            for(PlayerRef receiver : data.receivers()) {

                var voiceChannel = receiver.getPacketHandler().getChannel(StreamType.Voice);
                if (voiceChannel == null || !voiceChannel.isActive()) {
                    HytaleLogger.get("TTS broadcast").atInfo().log("no active voice channel for "+receiver.getUsername()+"|"+receiver.getUuid());
                    continue;
                }

                //FIXME waiting for a fix when we could replace receiver's coordinates with null for more smooth positionless audio
                if(data.isPositionless) { //all this entire if will be gone when that happens
                    PositionSnapshot cachedposition = VoiceModule.get().getCachedPosition(receiver.getUuid());
                    Position position = new Position(cachedposition.x(), cachedposition.y(), cachedposition.z());
                    
                    RelayedVoiceData relay = new RelayedVoiceData();
                    relay.entityId = data.entityId();
                    relay.sequenceNumber = sequenceNumber++;
                    relay.timestamp = data.opusTimestamp();
                    relay.speakerIsUnderwater = data.isSubmerged().get();
                    relay.speakerPosition = position; 
                    relay.opusData = currentFrameData;
                    relay.speakerId = data.speaker();
                    voiceChannel.writeAndFlush(relay);
                } else {
                    RelayedVoiceData relay = new RelayedVoiceData();
                    relay.entityId = data.entityId();
                    relay.sequenceNumber = sequenceNumber++;
                    relay.timestamp = data.opusTimestamp();
                    relay.speakerIsUnderwater = data.isSubmerged().get();
                    relay.speakerPosition = data.position().get(); 
                    relay.opusData = currentFrameData;
                    relay.speakerId = data.speaker();
                    voiceChannel.writeAndFlush(relay);
                }
            }
            
            toBroadcast.compute(entry.getKey(), (key, value) -> {
                return value.advance();
            });
        }
        
        toBroadcast.forEach((future,data) -> {
            if(data.audioData().isEmpty()) {
                future.complete(null);
            }
        });
        
        toBroadcast.entrySet().removeIf(entry -> entry.getKey().isDone()); //cancelled or completed otherwise
        
    }
    
    //public void broadcast
    
    
    public void cancelSpeech(UUID speaker) {
        toBroadcast.entrySet().removeIf(entry -> entry.getValue().speaker().equals(speaker));
    }
    
    public CompletableFuture<Void> broadcastAtSpeaker(UUID speaker, int entityId, List<byte[]> audioData, Collection<PlayerRef> receivers) {
        return broadcastAtSpeaker(speaker, entityId, audioData, receivers, false);
    }
    public CompletableFuture<Void> broadcastAtSpeaker(UUID speaker, int entityId, List<byte[]> audioData, Collection<PlayerRef> receivers, boolean ignoreSubmerged) {
        return broadcastAtPosition(speaker, entityId, audioData, receivers, 
        () -> {
            if(ignoreSubmerged) return true;
            return (Boolean)VoiceModule.get().getCachedPosition(speaker).isUnderwater();
        },
        ()->{
            PositionSnapshot cachedposition = VoiceModule.get().getCachedPosition(speaker);
            Position position = new Position(cachedposition.x(), cachedposition.y(), cachedposition.z());
            return position;
        });
    }

    public CompletableFuture<Void> broadcastPositionless(UUID speaker, int entityId, List<byte[]> audioData, Collection<PlayerRef> receivers) {
        return broadcastPositionless(speaker, entityId, audioData, receivers, false);
    }
    public CompletableFuture<Void> broadcastPositionless(UUID speaker, int entityId, List<byte[]> audioData, Collection<PlayerRef> receivers, boolean isSubmerged) {
        //broadcastAtPosition(speaker, entityId, audioData, receivers, ()->isSubmerged, () -> null); //TODO update when the patch for positionless audio is implemented
        
        if(audioData.isEmpty() || receivers.isEmpty()) return CompletableFuture.completedFuture(null);
        
        CompletableFuture<Void> future = new CompletableFuture<Void>();
        BroadcastData newData = new BroadcastData(speaker, entityId, audioData, receivers, ()->isSubmerged, ()->null, 0, true);
        toBroadcast.put(future, newData);
        return future;
    }

    public CompletableFuture<Void> broadcastAtPosition(UUID speaker, int entityId, List<byte[]> audioData, Collection<PlayerRef> receivers, boolean isSubmerged, Position position) {
        return broadcastAtPosition(speaker, entityId, audioData, receivers, () -> isSubmerged, () -> position);
    }
    public CompletableFuture<Void> broadcastAtPosition(UUID speaker, int entityId, List<byte[]> audioData, Collection<PlayerRef> receivers, Supplier<Boolean> isSubmerged, Supplier<Position> position) {
        if(audioData.isEmpty() || receivers.isEmpty()) return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> future = new CompletableFuture<Void>();
        BroadcastData newData = new BroadcastData(speaker, entityId, audioData, receivers, isSubmerged, position, 0, false);
        toBroadcast.put(future, newData);
        return future;
    }

}
