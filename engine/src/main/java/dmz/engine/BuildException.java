package dmz.engine;

/** A build step failed; the message is shown to the user as-is. */
public class BuildException extends Exception {
    public BuildException(String message) { super(message); }
    public BuildException(String message, Throwable cause) { super(message, cause); }
}
