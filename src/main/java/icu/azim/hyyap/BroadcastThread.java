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

    public record BroadcastData(UUID speaker, int entityId, List<byte[]> audioData, Collection<PlayerRef> receivers, Supplier<Boolean> isSubmerged, Supplier<Position> position, int opusTimestamp, @Deprecated boolean isPositionless) {
        BroadcastData advance() { //pop first opus frame and step opusTimestamp
            if (audioData.isEmpty()) {
                return this;//should never be called
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
            
            if(data.receivers().isEmpty()) { //no target listeners - should only happen when stopSpeakingTo removes every listener
                entry.getKey().cancel(true);
                continue;
            }
            
            for(PlayerRef receiver : data.receivers()) {

                var voiceChannel = receiver.getPacketHandler().getChannel(StreamType.Voice);
                if (voiceChannel == null || !voiceChannel.isActive()) {
                    //player has voice chat disabled
                    HytaleLogger.get("TTS broadcast").atInfo().log("no active voice channel for "+receiver.getUsername()+"|"+receiver.getUuid());
                    continue;
                }

                //FIXME waiting for a fix when we could replace receiver's coordinates with null for more smooth positionless audio
                if(data.isPositionless()) { //all this will be gone when that happens
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
                    relay.sequenceNumber = sequenceNumber++; //still no info on what sequenceNumber is for, incrementing it with each packet just in case
                    relay.timestamp = data.opusTimestamp();
                    relay.speakerIsUnderwater = data.isSubmerged().get();
                    relay.speakerPosition = data.position().get(); 
                    relay.opusData = currentFrameData;
                    relay.speakerId = data.speaker();
                    voiceChannel.writeAndFlush(relay);
                }
            }
            
            toBroadcast.compute(entry.getKey(), (key, value) -> value.advance()); //pop processed frame
        }
        
        toBroadcast.forEach((future,data) -> {
            if(data.audioData().isEmpty()) { //complete all spoken futures 
                future.complete(null);
            }
        });
        
        toBroadcast.entrySet().removeIf(entry -> entry.getKey().isDone()); //remove cancelled or otherwise completed entries
        
    }
    
    
    /**
     * Stops all broadcasts from which originate from provided UUID. Associated CompletableFutures are cancelled.
     * 
     * @param speaker UUID of the speaker to interrupt broadcast of
     */
    public void stopBroadcasts(UUID speaker) {
        toBroadcast.forEach((k, v) -> {
            if (v.speaker().equals(speaker)) {
                k.cancel(true);
            }
        });
    }

    /**
     * Removes all broadcasts from speaker to receiver. <br>
     * If after that, there are no receivers for a given broadcast, it's stopped and associated CompletableFuture is cancelled.
     * 
     * @param speaker UUID of the speaker to interrupt broadcast of
     * @param receiver PlayerRef of the receiver to remove
     */
    public void stopBroadcastsTo(UUID speaker, PlayerRef receiver) {
        stopBroadcastsTo(speaker, List.of(receiver));
    }
    
    /**
     * Removes all broadcasts from speaker to receivers. <br>
     * If after that, there are no receivers for a given broadcast, it's stopped and associated CompletableFuture is cancelled.
     * 
     * @param speaker UUID of the speaker to interrupt broadcast of
     * @param receiver PlayerRef of the receiver to remove
     */
    public void stopBroadcastsTo(UUID speaker, Collection<PlayerRef> receivers) {
        toBroadcast.forEach((k, v) -> {
            if (v.speaker().equals(speaker)) {
                toBroadcast.computeIfPresent(k, (key, val) -> {
                    val.receivers.removeAll(receivers);
                    return val;
                });
            }
        });
    }

    /**
     * Checks if there are any ongoing broadcasts from provided speaker
     * 
     * @param speaker UUID of the speaker
     * @return true if there are any ongoing broadcasts from provided speaker
     */
    public boolean isBroadcasting(UUID speaker) {
        return toBroadcast.searchEntries(1, entry -> {
            if(entry.getKey().isDone()) return null;
            if(entry.getValue().speaker().equals(speaker)) return Boolean.TRUE;
            return null;
        })!=null;
    }

    /**
     * Checks if there are any ongoing broadcasts from provided speaker to receiver
     * 
     * @param speaker UUID of the speaker
     * @param receiver PlayerRef of the receiver
     * @return true if there are any ongoing broadcasts from provided speaker to receiver
     */
    public boolean isBroadcastingTo(UUID speaker, PlayerRef receiver) {
        return toBroadcast.searchEntries(1, entry -> {
            if(entry.getKey().isDone()) return null;
            if(entry.getValue().speaker().equals(speaker)  && entry.getValue().receivers().contains(receiver)) return Boolean.TRUE;
            return null;
        })!=null;
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
        //broadcastAtPosition(speaker, entityId, audioData, receivers, ()->isSubmerged, () -> null); //FIXME update when the patch for positionless audio is implemented
        
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
