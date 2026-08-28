package net.exylia.lib.config;

/**
 * A section that is only written to file when it does something.
 *
 * <p>The config module writes every key of every section, which is what makes a
 * file self-documenting: an owner opens it and sees what can be set. For a
 * section that is off, that stops being help and becomes noise — an effect with
 * one boss bar wrote a title, an action bar, a sound, particles and a firework
 * around it, every one of them empty, every one of them with its own comment.
 * Fifteen effects in a plugin's config is a thousand lines of nothing.
 *
 * <p>A record implementing this decides for itself when it has nothing to say.
 * While it says so, the section is left out of the file entirely and reading it
 * back is silent: the defaults are used and nothing is reported missing, so the
 * file does not grow the block back on the next load.
 *
 * <p>Only for sections whose defaults are empty by nature. A section with real
 * defaults must stay in the file, or nobody can find out it exists.
 *
 * @since 1.73.0
 */
public interface Sparse {

    /**
     * Returns whether this section does nothing, and is therefore not worth a
     * block in the file.
     *
     * <p>The same question the runtime asks before playing it, so a section that
     * writes itself is exactly a section that would have an effect.
     */
    boolean isEmpty();
}
