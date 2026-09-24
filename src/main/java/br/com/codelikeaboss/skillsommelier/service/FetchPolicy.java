package br.com.codelikeaboss.skillsommelier.service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Strategy deciding whether a cached clone is used as is or fetched from its remote first. Called while
 * holding the clone's lock.
 */
@FunctionalInterface
interface FetchPolicy {

    /**
     * @return true to clone or fetch, false to use the cached clone as is
     * @throws IOException when the clone can neither be used nor fetched
     */
    boolean shouldFetch(CachedClone clone) throws IOException;

    /** Clones sources that are not cached yet, and never updates them. */
    static FetchPolicy whenMissing() {
        return clone -> !clone.isCloned();
    }

    /** Always goes to the network. */
    static FetchPolicy always() {
        return clone -> true;
    }

    /** Never goes to the network; fails when the source was never cloned. */
    static FetchPolicy offline() {
        return clone -> {
            if (!clone.isCloned()) {
                throw new IOException("offline and not cached yet");
            }
            return false;
        };
    }

    /**
     * Goes to the network at most once per {@code maxAge}, counting failed attempts too, so an unreachable
     * source does not slow down every build.
     */
    static FetchPolicy throttled(Duration maxAge) {
        return clone -> {
            Optional<Instant> lastAttempt = clone.lastAttempt();
            boolean recent = lastAttempt.isPresent() && lastAttempt.get().plus(maxAge).isAfter(Instant.now());
            if (!recent) {
                return true;
            }
            if (clone.isCloned()) {
                return false;
            }
            throw new IOException("last attempt at " + lastAttempt.get() + " failed (" + clone.lastStatus()
                    + "); retrying after the update interval");
        };
    }
}
