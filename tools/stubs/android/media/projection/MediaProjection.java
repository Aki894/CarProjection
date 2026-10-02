package android.media.projection;

/** JVM-only fixture, never packaged into the Android app. */
public class MediaProjection {
    public boolean stopped;
    public void stop() { stopped = true; }
}
