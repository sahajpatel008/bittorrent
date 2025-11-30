package bittorrent.service;

import java.io.File;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import bittorrent.peer.Peer;

/**
 * Represents an asynchronous download job.
 */
public class DownloadJob {
    public enum Status {
        PENDING,
        DOWNLOADING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    private final String jobId;
    private final String infoHashHex;
    private final String fileName;
    private Status status;
    private File downloadedFile;
    private String errorMessage;
    private int totalPieces;
    private int completedPieces;
    private List<Peer> activePeers;
    private CompletableFuture<File> future;

    public DownloadJob(String infoHashHex, String fileName) {
        this.jobId = UUID.randomUUID().toString();
        this.infoHashHex = infoHashHex;
        this.fileName = fileName;
        this.status = Status.PENDING;
        this.totalPieces = 0;
        this.completedPieces = 0;
    }

    public String getJobId() {
        return jobId;
    }

    public String getInfoHashHex() {
        return infoHashHex;
    }

    public String getFileName() {
        return fileName;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public File getDownloadedFile() {
        return downloadedFile;
    }

    public void setDownloadedFile(File downloadedFile) {
        this.downloadedFile = downloadedFile;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public int getTotalPieces() {
        return totalPieces;
    }

    public void setTotalPieces(int totalPieces) {
        this.totalPieces = totalPieces;
    }

    public int getCompletedPieces() {
        return completedPieces;
    }

    public void setCompletedPieces(int completedPieces) {
        this.completedPieces = completedPieces;
    }

    public double getProgress() {
        if (totalPieces == 0) {
            return 0.0;
        }
        return (double) completedPieces / totalPieces * 100.0;
    }

    public List<Peer> getActivePeers() {
        return activePeers;
    }

    public void setActivePeers(List<Peer> activePeers) {
        this.activePeers = activePeers;
    }

    public CompletableFuture<File> getFuture() {
        return future;
    }

    public void setFuture(CompletableFuture<File> future) {
        this.future = future;
    }
}

