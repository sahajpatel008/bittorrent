package bittorrent.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Service for broadcasting real-time torrent progress updates via SSE
 */
@Service
public class TorrentProgressService {
    
    // Map<jobId, SseEmitter> - clients subscribed to specific jobs
    private final Map<String, SseEmitter> jobSubscribers = new ConcurrentHashMap<>();
    
    // Map<infoHash, SseEmitter> - clients subscribed to all torrents
    private final Map<String, SseEmitter> torrentSubscribers = new ConcurrentHashMap<>();
    
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    
    private BitTorrentService bitTorrentService;
    
    public TorrentProgressService() {
        // Start periodic updates every second
        scheduler.scheduleAtFixedRate(this::broadcastUpdates, 1, 1, TimeUnit.SECONDS);
    }
    
    @Autowired
    public void setBitTorrentService(@Lazy BitTorrentService bitTorrentService) {
        this.bitTorrentService = bitTorrentService;
    }
    
    /**
     * Subscribe to updates for a specific download job
     */
    public SseEmitter subscribeToJob(String jobId) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        emitter.onCompletion(() -> jobSubscribers.remove(jobId));
        emitter.onTimeout(() -> jobSubscribers.remove(jobId));
        emitter.onError((ex) -> jobSubscribers.remove(jobId));
        
        jobSubscribers.put(jobId, emitter);
        return emitter;
    }
    
    /**
     * Subscribe to updates for a specific torrent (all jobs for that torrent)
     */
    public SseEmitter subscribeToTorrent(String infoHash) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        emitter.onCompletion(() -> torrentSubscribers.remove(infoHash));
        emitter.onTimeout(() -> torrentSubscribers.remove(infoHash));
        emitter.onError((ex) -> torrentSubscribers.remove(infoHash));
        
        torrentSubscribers.put(infoHash, emitter);
        return emitter;
    }
    
    /**
     * Send update to subscribers
     */
    public void sendUpdate(String jobId, String infoHash, Map<String, Object> data) {
        // Send to job-specific subscribers
        SseEmitter jobEmitter = jobSubscribers.get(jobId);
        if (jobEmitter != null) {
            try {
                jobEmitter.send(SseEmitter.event()
                    .name("progress")
                    .data(data));
            } catch (IOException e) {
                jobSubscribers.remove(jobId);
            }
        }
        
        // Send to torrent-specific subscribers
        SseEmitter torrentEmitter = torrentSubscribers.get(infoHash);
        if (torrentEmitter != null) {
            try {
                torrentEmitter.send(SseEmitter.event()
                    .name("progress")
                    .data(data));
            } catch (IOException e) {
                torrentSubscribers.remove(infoHash);
            }
        }
    }
    
    /**
     * Broadcast periodic updates for all active jobs
     */
    private void broadcastUpdates() {
        if (bitTorrentService == null) return;
        
        // Poll all active jobs and send updates
        for (DownloadJob job : bitTorrentService.getAllActiveJobs()) {
            if (job.getStatus() == DownloadJob.Status.DOWNLOADING) {
                Map<String, Object> update = bitTorrentService.buildProgressUpdate(job);
                sendUpdate(job.getJobId(), job.getInfoHashHex(), update);
            }
        }
    }
}

