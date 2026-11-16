package dmz.engine;

/** Receives build output. Implemented by the app (console + progress bar) and the desktop harness. */
public interface BuildLog {
    /** level: INFO, OK, WARN, ERROR, CMD */
    void log(String level, String message);

    /** Overall progress 0-100 and a short label for the current step. */
    void progress(int percent, String step);

    BuildLog STDOUT = new BuildLog() {
        @Override public void log(String level, String message) { System.out.println("[" + level + "] " + message); }
        @Override public void progress(int percent, String step) { System.out.println("== " + percent + "% " + step); }
    };
}
