package org.gms.soloacceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Read-only capture owns only its sampling carrier and target-scene event subscription. */
@Service
@ConditionalOnProperty(prefix = "solomapling.acceptance", name = "enabled", havingValue = "true", matchIfMissing = false)
public final class SceneCaptureService {
    private static final int MAX_ACTIVE = 4;
    private static final int HISTORY_CAPACITY = 256;
    private static final int MAX_RECORD_BYTES = 32 * 1024 * 1024;
    private static final long MAX_RECORDING_BYTES = 256L * 1024 * 1024;
    private final AcceptanceObservationService observations;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Recording> recordings = new LinkedHashMap<>();
    private int active;

    public SceneCaptureService(AcceptanceObservationService observations) { this.observations = observations; }

    public static Map<String, Object> publicCapabilities() {
        var result = new LinkedHashMap<String, Object>();
        result.put("schema_version", 2);
        result.put("enabled_tools", java.util.List.of("scene_snapshot", "scene_record"));
        result.put("actor_required", false);
        result.put("read_only", true);
        result.put("default_duration_ms", 10_000);
        result.put("minimum_duration_ms", 100);
        result.put("maximum_duration_ms", 120_000);
        result.put("default_interval_ms", 100);
        result.put("minimum_interval_ms", 50);
        result.put("maximum_interval_ms", 5_000);
        result.put("maximum_active_recordings", MAX_ACTIVE);
        result.put("terminal_history_capacity", HISTORY_CAPACITY);
        result.put("terminal_history_policy", "fifo_safe_summaries_without_native_references");
        result.put("maximum_inline_snapshot_bytes", 4 * 1024 * 1024);
        result.put("maximum_logical_record_bytes", MAX_RECORD_BYTES);
        result.put("maximum_recording_bytes", MAX_RECORDING_BYTES);
        result.put("event_coverage", SceneCaptureEvents.coverage());
        result.put("snapshot_consistency", "best_effort");
        result.put("snapshot_view", "server_scene_state_not_recipient_initialization");
        result.put("recording_model", "baseline_output_events_and_state_checkpoints");
        result.put("interval_semantics", "event_batch_flush_and_state_checkpoint_only");
        return Map.copyOf(result);
    }

    public Map<String, Object> snapshot(String sceneRef) {
        var target = observations.captureTarget(sceneRef);
        var started = Instant.now();
        long baseline = System.nanoTime();
        var staticScene = staticScene(target);
        var frame = new LinkedHashMap<>(SceneCaptureFrames.frame(target.map()));
        observations.requireCaptureCurrent(target);
        frame.put("seq", 1);
        frame.put("started_at", started.toString());
        frame.put("finished_at", Instant.now().toString());
        frame.put("elapsed_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - baseline));
        frame.put("events", Map.of("items", java.util.List.of(), "coverage",
                Map.of("complete", false, "reason", "snapshot_has_no_observation_interval")));
        var result = new LinkedHashMap<String, Object>();
        result.put("schema_version", 2);
        result.put("information_level", "server_diagnostic");
        result.put("snapshot_consistency", "best_effort");
        result.put("snapshot_view", "server_scene_state_not_recipient_initialization");
        result.put("identity", target.identity());
        result.put("source", observations.captureSourceIdentity());
        result.put("static_scene", staticScene);
        result.put("frame", Map.copyOf(frame));
        try { encode(result); }
        catch (IOException encoding) { throw new IllegalStateException("scene_capture_encoding_failed", encoding); }
        return Map.copyOf(result);
    }

    /** A single servlet carrier produces the complete stream; no detached sampler can outlive it. */
    public void record(String owner, SceneCaptureRequest.Record request, OutputStream output) throws IOException {
        var target = observations.captureTarget(request.sceneRef());
        var recording = begin(owner);
        var started = Instant.now();
        long baseline = System.nanoTime();
        long interval = TimeUnit.MILLISECONDS.toNanos(request.intervalMs());
        long deadline = baseline + TimeUnit.MILLISECONDS.toNanos(request.durationMs());
        long next = baseline;
        long frames = 0;
        long eventBatches = 0;
        long bytes = 0;
        long missed = 0;
        long droppedEvents = 0;
        long probeFailures = 0;
        long failedObjectReads = 0;
        String reason = "capture_failed";
        boolean released = false;
        boolean fileComplete = false;
        boolean headerWritten = false;
        SceneCaptureEvents.Subscription subscription = null;
        Map<String, Object> tailEvents = Map.of();
        Map<String, Object> unwrittenEvents = Map.of();
        try {
            subscription = SceneCaptureEvents.open(target.map());
            var header = new LinkedHashMap<String, Object>();
            header.put("type", "header");
            header.put("schema_version", 2);
            header.put("capture_id", recording.id);
            header.put("identity", target.identity());
            header.put("source", observations.captureSourceIdentity());
            header.put("information_level", "observed_server_output_and_diagnostic_state");
            header.put("snapshot_consistency", "best_effort");
            header.put("snapshot_view", "server_scene_state_not_recipient_initialization");
            header.put("recording_model", "baseline_output_events_and_state_checkpoints");
            header.put("interval_semantics", "event_batch_flush_and_state_checkpoint_only");
            header.put("capture_start", "mid_scene_no_prior_output_replay");
            header.put("baseline_event_overlap", "subscription_open_before_baseline_read_events_may_overlap_read_interval");
            header.put("started_at", started.toString());
            header.put("settings", Map.of("duration_ms", request.durationMs(), "interval_ms", request.intervalMs()));
            header.put("static_scene", staticScene(target));
            header.put("event_coverage", SceneCaptureEvents.coverage());
            bytes += write(output, header);
            headerWritten = true;
            while (true) {
                if (recording.cancelRequested) { reason = "cancelled"; break; }
                if (subscription.sceneDisposed()) { reason = "scene_destroyed"; break; }
                observations.requireCaptureCurrent(target);
                if (frames > 0 && System.nanoTime() >= deadline) { reason = "duration_elapsed"; break; }
                var frameStarted = Instant.now();
                var frame = new LinkedHashMap<>(SceneCaptureFrames.frame(target.map()));
                observations.requireCaptureCurrent(target);
                if (subscription.sceneDisposed()) { reason = "scene_destroyed"; break; }
                frame.put("type", frames == 0 ? "baseline" : "checkpoint");
                frame.put("seq", frames + 1);
                frame.put("started_at", frameStarted.toString());
                var events = subscription.drain();
                unwrittenEvents = events;
                droppedEvents += (long) events.get("dropped");
                probeFailures += (long) events.get("probe_read_failures");
                var coverage = (Map<?, ?>) frame.get("coverage");
                failedObjectReads += (int) coverage.get("failed_object_count");
                frame.put("finished_at", Instant.now().toString());
                frame.put("elapsed_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - baseline));
                frame.put("missed_intervals", missed);
                var encoded = encode(frame);
                var eventBatch = Map.<String, Object>of("type", "events", "seq", eventBatches + 1,
                        "events", events, "elapsed_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - baseline));
                var encodedEvents = encode(eventBatch);
                // Reserve one logical record for the final summary and trailing frozen events.
                if (bytes + encoded.length + encodedEvents.length + 2 > MAX_RECORDING_BYTES - MAX_RECORD_BYTES) {
                    reason = "recording_size_limit";
                    break;
                }
                output.write(encoded);
                output.write('\n');
                output.flush();
                bytes += encoded.length + 1;
                frames++;
                output.write(encodedEvents);
                output.write('\n');
                output.flush();
                bytes += encodedEvents.length + 1;
                eventBatches++;
                unwrittenEvents = Map.of();
                next += interval;
                long now = System.nanoTime();
                if (next <= now) {
                    long skipped = (now - next) / interval + 1;
                    missed += skipped;
                    next += skipped * interval;
                }
                long wait = Math.min(next, deadline) - now;
                if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait);
            }
        } catch (InterruptedException interrupted) {
            reason = recording.cancelRequested ? "cancelled" : "interrupted";
        } catch (ObservationException unavailable) {
            reason = unavailable.reason();
            if (!headerWritten) throw unavailable;
        } catch (IOException transport) {
            reason = "output_failed";
            throw transport;
        } finally {
            if (subscription != null) {
                subscription.close();
                released = true;
                tailEvents = subscription.drain();
                droppedEvents += (long) tailEvents.get("dropped");
                probeFailures += (long) tailEvents.get("probe_read_failures");
            } else {
                released = true;
            }
            var summary = new LinkedHashMap<String, Object>();
            summary.put("type", "end");
            summary.put("capture_id", recording.id);
            summary.put("reason", reason);
            summary.put("started_at", started.toString());
            summary.put("finished_at", Instant.now().toString());
            summary.put("frame_count", frames);
            summary.put("event_batch_count", eventBatches);
            summary.put("elapsed_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - baseline));
            summary.put("missed_intervals", missed);
            summary.put("settings", Map.of("duration_ms", request.durationMs(), "interval_ms", request.intervalMs()));
            summary.put("completed_normally", "duration_elapsed".equals(reason));
            summary.put("recording_complete", "duration_elapsed".equals(reason));
            summary.put("coverage", Map.of("complete", false,
                    "reason", "declared_output_protocols_and_diagnostic_sources_are_partial",
                    "dropped_event_count", droppedEvents, "probe_read_failures", probeFailures,
                    "failed_object_reads", failedObjectReads, "unwritten_sample", !unwrittenEvents.isEmpty()));
            summary.put("sampling_stopped", true);
            summary.put("subscription_released", released);
            try {
                if (headerWritten && !"output_failed".equals(reason) && !"capture_failed".equals(reason)) {
                    var end = new LinkedHashMap<>(summary);
                    end.put("events", tailEvents);
                    if (!unwrittenEvents.isEmpty()) end.put("unwritten_frame_events", unwrittenEvents);
                    end.put("file_complete", true);
                    bytes += write(output, end);
                    fileComplete = true;
                }
            } finally {
                summary.put("file_complete", fileComplete);
                summary.put("bytes_written", bytes);
                recording.finish(Map.copyOf(summary));
                synchronized (recordings) { active--; }
                // A caller-issued cancel uses interruption only to wake this carrier's wait.
                if (recording.cancelRequested) Thread.interrupted();
            }
        }
    }

    public Map<String, Object> status(String owner, String id) { return find(owner, id).snapshot(); }

    public Map<String, Object> cancel(String owner, String id) {
        var recording = find(owner, id);
        recording.cancel();
        return recording.snapshot();
    }

    private Map<String, Object> staticScene(AcceptanceObservationService.CaptureTarget target) {
        try { return SceneCaptureFrames.staticScene(target.map()); }
        catch (RuntimeException unavailable) {
            observations.requireCaptureCurrent(target);
            return Map.of("read_status", "unavailable", "reason", "native_static_scene_read_failed",
                    "coverage", Map.of("complete", false));
        }
    }

    private Recording begin(String owner) {
        synchronized (recordings) {
            if (active >= MAX_ACTIVE) throw new ObservationException(HttpStatus.TOO_MANY_REQUESTS, "capture_capacity_exceeded");
            if (recordings.size() >= HISTORY_CAPACITY) {
                var iterator = recordings.values().iterator();
                while (iterator.hasNext()) {
                    if (iterator.next().finished != null) { iterator.remove(); break; }
                }
            }
            var recording = new Recording(owner);
            recordings.put(recording.id, recording);
            active++;
            return recording;
        }
    }

    private Recording find(String owner, String id) {
        synchronized (recordings) {
            var recording = recordings.get(id);
            if (recording == null || !recording.owner.equals(owner)) {
                throw new ObservationException(HttpStatus.NOT_FOUND, "capture_not_found");
            }
            return recording;
        }
    }

    private byte[] encode(Map<String, Object> record) throws IOException {
        var bytes = json.writeValueAsBytes(record);
        if (bytes.length > MAX_RECORD_BYTES) {
            throw new ObservationException(HttpStatus.PAYLOAD_TOO_LARGE, "capture_record_size_limit");
        }
        return bytes;
    }

    private long write(OutputStream output, Map<String, Object> record) throws IOException {
        var bytes = encode(record);
        output.write(bytes);
        output.write('\n');
        output.flush();
        return bytes.length + 1L;
    }

    private static final class Recording {
        private final String id = "capture-" + UUID.randomUUID();
        private final String owner;
        private volatile Thread carrier = Thread.currentThread();
        private volatile boolean cancelRequested;
        private volatile Map<String, Object> finished;

        private Recording(String owner) { this.owner = owner; }

        private synchronized void cancel() {
            if (finished == null) {
                cancelRequested = true;
                if (carrier != null) carrier.interrupt();
            }
        }

        private synchronized void finish(Map<String, Object> summary) { carrier = null; finished = summary; }

        private Map<String, Object> snapshot() {
            var result = finished;
            return result == null ? Map.of("capture_id", id, "sampling_stopped", false,
                    "subscription_released", false, "file_complete", false,
                    "cancel_requested", cancelRequested) : result;
        }
    }
}
