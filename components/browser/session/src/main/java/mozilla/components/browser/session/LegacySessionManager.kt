/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.browser.session

import android.support.annotation.GuardedBy
import mozilla.components.browser.session.engine.EngineObserver
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.EngineSessionState
import mozilla.components.support.base.observer.Observable
import mozilla.components.support.base.observer.ObserverRegistry
import kotlin.math.max

/**
 * This class provides access to a centralized registry of all active sessions.
 */
@Suppress("TooManyFunctions", "LargeClass")
class LegacySessionManager(
    val engine: Engine,
    val defaultSession: (() -> Session)? = null,
    delegate: Observable<SessionManager.Observer> = ObserverRegistry()
) : Observable<SessionManager.Observer> by delegate {
    private val values = mutableListOf<Session>()

    @GuardedBy("values")
    private var selectedIndex: Int = NO_SELECTION

    /**
     * Returns the number of session including CustomTab sessions.
     */
    val size: Int
        get() = synchronized(values) { values.size }

    /**
     * Produces a snapshot of this manager's state, suitable for restoring via [SessionManager.restore].
     * Only regular sessions are included in the snapshot. Private and Custom Tab sessions are omitted.
     *
     * @return [Snapshot] of the current session state.
     */
    fun createSnapshot(): SessionManager.Snapshot = synchronized(values) {
        if (values.isEmpty()) {
            return SessionManager.Snapshot.empty()
        }

        // Filter out CustomTab and private sessions.
        // We're using 'values' directly instead of 'sessions' to get benefits of a sequence.
        val sessionStateTuples = values.asSequence()
                .filter { !it.isCustomTabSession() }
                .filter { !it.private }
                .map { session ->
                    SessionManager.Snapshot.Item(
                        session,
                        session.engineSessionHolder.engineSession,
                        session.engineSessionHolder.engineSessionState
                    )
                }
                .toList()

        // We might have some sessions (private, custom tab) but none we'd include in the snapshot.
        if (sessionStateTuples.isEmpty()) {
            return SessionManager.Snapshot.empty()
        }

        // We need to find out the index of our selected session in the filtered list. If we have a
        // mix of private, custom tab and regular sessions, global selectedIndex isn't good enough.
        // We must have a selectedSession if there is at least one "regular" (non-CustomTabs) session
        // present. Selected session might be private, in which case we reset our selection index to 0.
        var selectedIndexAfterFiltering = 0
        selectedSession?.takeIf { !it.private }?.let { selected ->
            sessionStateTuples.find { it.session.id == selected.id }?.let { selectedTuple ->
                selectedIndexAfterFiltering = sessionStateTuples.indexOf(selectedTuple)
            }
        }

        // Sanity check to guard against producing invalid snapshots.
        checkNotNull(sessionStateTuples.getOrNull(selectedIndexAfterFiltering)) {
            "Selection index after filtering session must be valid"
        }

        SessionManager.Snapshot(
            sessions = sessionStateTuples,
            selectedSessionIndex = selectedIndexAfterFiltering
        )
    }

    /**
     * Gets the currently selected session if there is one.
     *
     * Only one session can be selected at a given time.
     */
    val selectedSession: Session?
        get() = synchronized(values) {
            if (selectedIndex == NO_SELECTION) {
                null
            } else {
                values[selectedIndex]
            }
        }

    /**
     * Gets the currently selected session or throws an IllegalStateException if no session is
     * selected.
     *
     * It's application specific whether a session manager can have no selected session (= no sessions)
     * or not. In applications that always have at least one session dealing with the nullable
     * <code>selectedSession</code> property can be cumbersome. In those situations, where you always
     * expect a session to exist, you can use <code>selectedSessionOrThrow</code> to avoid dealing
     * with null values.
     *
     * Only one session can be selected at a given time.
     */
    val selectedSessionOrThrow: Session
        get() = selectedSession ?: throw IllegalStateException("No selected session")

    /**
     * Returns a list of active sessions and filters out sessions used for CustomTabs.
     */
    val sessions: List<Session>
        get() = synchronized(values) { values.filter { !it.isCustomTabSession() } }

    /**
     * Returns a list of all active sessions (including CustomTab sessions).
     */
    val all: List<Session>
        get() = synchronized(values) { values.toList() }

    /**
     * Adds the provided session.
     */
    fun add(
        session: Session,
        selected: Boolean = false,
        engineSession: EngineSession? = null,
        parent: Session? = null
    ) = synchronized(values) {
        addInternal(session, selected, engineSession, parent = parent, viaRestore = false)
    }

    @Suppress("LongParameterList")
    private fun addInternal(
        session: Session,
        selected: Boolean = false,
        engineSession: EngineSession? = null,
        engineSessionState: EngineSessionState? = null,
        parent: Session? = null,
        viaRestore: Boolean = false
    ) = synchronized(values) {
        if (parent != null) {
            val parentIndex = values.indexOf(parent)

            if (parentIndex == -1) {
                throw IllegalArgumentException("The parent does not exist")
            }

            session.parentId = parent.id

            values.add(parentIndex + 1, session)
        } else {
            values.add(session)
        }

        if (engineSession != null) {
            link(session, engineSession)
        } else if (engineSessionState != null) {
            session.engineSessionHolder.engineSessionState = engineSessionState
        }

        // If session is being added via restore, skip notification and auto-selection.
        // Restore will handle these actions as appropriate.
        if (viaRestore) {
            return@synchronized
        }

        notifyObservers { onSessionAdded(session) }

        // Auto-select incoming session if we don't have a currently selected one.
        if (selected || selectedIndex == NO_SELECTION && !session.isCustomTabSession()) {
            select(session)
        }
    }

    /**
     * Restores sessions from the provided [Snapshot].
     * Notification behaviour is as follows:
     * - onSessionAdded notifications will not fire,
     * - onSessionSelected notification will fire exactly once if the snapshot isn't empty,
     * - once snapshot has been restored, and appropriate session has been selected, onSessionsRestored
     *   notification will fire.
     *
     * @param snapshot A [Snapshot] which may be produced by [createSnapshot].
     */
    fun restore(snapshot: SessionManager.Snapshot) = synchronized(values) {
        if (snapshot.sessions.isEmpty()) {
            return
        }

        // Let's be forgiving about incoming illegal selection indices, but strict about what we
        // produce ourselves in `createSnapshot`.
        val sessionTupleToSelect = snapshot.sessions.getOrElse(snapshot.selectedSessionIndex) {
            // Default to the first session if we received an illegal selection index.
            snapshot.sessions[0]
        }

        snapshot.sessions.forEach {
            addInternal(
                it.session,
                engineSession = it.engineSession,
                engineSessionState = it.engineSessionState,
                parent = null,
                viaRestore = true)
        }

        select(sessionTupleToSelect.session)

        notifyObservers { onSessionsRestored() }
    }

    /**
     * Gets the linked engine session for the provided session (if it exists).
     */
    fun getEngineSession(session: Session = selectedSessionOrThrow) = session.engineSessionHolder.engineSession

    /**
     * Gets the linked engine session for the provided session and creates it if needed.
     */
    fun getOrCreateEngineSession(session: Session = selectedSessionOrThrow): EngineSession {
        getEngineSession(session)?.let { return it }

        return engine.createSession(session.private).apply {
            session.engineSessionHolder.engineSessionState?.let { state ->
                restoreState(state)
                session.engineSessionHolder.engineSessionState = null
            }

            link(session, this)
        }
    }

    fun link(session: Session, engineSession: EngineSession) {
        unlink(session)

        session.engineSessionHolder.apply {
            this.engineSession = engineSession
            this.engineObserver = EngineObserver(session).also { observer ->
                engineSession.register(observer)
                engineSession.loadUrl(session.url)
            }
        }
    }

    private fun unlink(session: Session) {
        session.engineSessionHolder.engineObserver?.let { observer ->
            session.engineSessionHolder.apply {
                engineSession?.unregister(observer)
                engineSession?.close()
                engineSession = null
                engineSessionState = null
                engineObserver = null
            }
        }
    }

    /**
     * Removes the provided session. If no session is provided then the selected session is removed.
     */
    fun remove(
        session: Session = selectedSessionOrThrow,
        selectParentIfExists: Boolean = false
    ) = synchronized(values) {
        val indexToRemove = values.indexOf(session)
        if (indexToRemove == -1) {
            return
        }

        values.removeAt(indexToRemove)

        unlink(session)

        val selectionUpdated = recalculateSelectionIndex(
                indexToRemove,
                selectParentIfExists,
                session.private,
                session.parentId
        )

        values.filter { it.parentId == session.id }
            .forEach { child -> child.parentId = session.parentId }

        notifyObservers { onSessionRemoved(session) }

        // NB: we're not explicitly calling notifyObservers here, since adding a session when none
        // are present will notify observers with onSessionSelected.
        if (selectedIndex == NO_SELECTION && defaultSession != null) {
            add(defaultSession.invoke())
            return
        }

        if (selectionUpdated && selectedIndex != NO_SELECTION) {
            notifyObservers { onSessionSelected(selectedSessionOrThrow) }
        }
    }

    /**
     * Recalculate the index of the selected session after a session was removed. Returns true if the index has changed.
     * False otherwise.
     */
    @Suppress("ComplexMethod")
    private fun recalculateSelectionIndex(
        indexToRemove: Int,
        selectParentIfExists: Boolean,
        private: Boolean,
        parentId: String?
    ): Boolean {
        // Recalculate selection
        var newSelectedIndex = when {
            // All items have been removed
            values.size == 0 -> NO_SELECTION

            // The selected session has been removed. We need a new selection.
            indexToRemove == selectedIndex -> newSelection(indexToRemove, selectParentIfExists, private, parentId)

            // Something on the left of our selection has been removed. We need to move the index to the left.
            indexToRemove < selectedIndex -> selectedIndex - 1

            // The selection is out of bounds. Let's select the previous item.
            selectedIndex == values.size -> selectedIndex - 1

            // We can keep the selected index.
            else -> selectedIndex
        }

        if (newSelectedIndex != NO_SELECTION && values[newSelectedIndex].isCustomTabSession()) {
            // Oh shoot! The session we want to select is a custom tab and that's a no no. Let's find a regular session
            // that is still close.
            newSelectedIndex = findNearbyNonCustomTabSession(
                // Find a new index. Either from the one we wanted to select or if that was the parent then start from
                // the index we just removed.
                if (selectParentIfExists) indexToRemove else newSelectedIndex)
        }

        val selectionUpdated = newSelectedIndex != selectedIndex

        if (selectionUpdated) {
            selectedIndex = newSelectedIndex
        }

        return selectionUpdated
    }

    private fun newSelection(
        index: Int,
        selectParentIfExists: Boolean,
        private: Boolean,
        parentId: String?
    ): Int {
        return if (selectParentIfExists && parentId != null) {
            // Select parent session
            val parent = findSessionById(parentId)
                ?: throw java.lang.IllegalStateException("Parent session referenced by id does not exist")

            values.indexOf(parent)
        } else {
            // Find session of same type (private|regular)
            if (index <= values.lastIndex && values[index].private == private) {
                return index
            }

            val nearbyMatch = findNearbySession(index) { it.private == private }

            // If there's no other private session to select we use the newest non-private one
            if (nearbyMatch == NO_SELECTION && private) values.lastIndex else nearbyMatch
        }
    }

    /**
     * @return Index of a new session that is not a custom tab and as close as possible to the given index. Returns
     * [NO_SELECTION] if no session to select could be found.
     */
    private fun findNearbyNonCustomTabSession(index: Int): Int {
        return findNearbySession(index) { !it.isCustomTabSession() }
    }

    private fun findNearbySession(index: Int, predicate: (Session) -> Boolean): Int {
        val maxSteps = max(values.lastIndex - index, index)

        if (maxSteps <= 0) {
            return NO_SELECTION
        }

        // Try sessions oscillating near the index.
        for (steps in 1..maxSteps) {
            listOf(index - steps, index + steps).forEach { current ->
                if (current >= 0 &&
                    current <= values.lastIndex &&
                    predicate(values[current])) {
                    return current
                }
            }
        }

        return NO_SELECTION
    }

    /**
     * Removes all sessions but CustomTab sessions.
     */
    fun removeSessions() = synchronized(values) {
        sessions.forEach {
            unlink(it)
            values.remove(it)
        }

        selectedIndex = NO_SELECTION

        // NB: This callback indicates to observers that either removeSessions or removeAll were
        // invoked, not that the manager is now empty.
        notifyObservers { onAllSessionsRemoved() }

        if (defaultSession != null) {
            add(defaultSession.invoke())
        }
    }

    /**
     * Removes all sessions including CustomTab sessions.
     */
    fun removeAll() = synchronized(values) {
        val onlyCustomTabSessionsPresent = values.isNotEmpty() && sessions.isEmpty()

        values.forEach { unlink(it) }
        values.clear()

        selectedIndex = NO_SELECTION

        // NB: This callback indicates to observers that either removeSessions or removeAll were
        // invoked, not that the manager is now empty.
        notifyObservers { onAllSessionsRemoved() }

        // Don't create a default session if we only had CustomTab sessions to begin with.
        if (!onlyCustomTabSessionsPresent && defaultSession != null) {
            add(defaultSession.invoke())
        }
    }

    /**
     * Marks the given session as selected.
     */
    fun select(session: Session) = synchronized(values) {
        val index = values.indexOf(session)

        require(index != -1) {
            "Value to select is not in list"
        }

        selectedIndex = index

        notifyObservers { onSessionSelected(session) }
    }

    /**
     * Finds and returns the session with the given id. Returns null if no matching session could be
     * found.
     */
    fun findSessionById(id: String): Session? = values.find { session -> session.id == id }

    /**
     * Informs this [SessionManager] that the OS is in low memory condition so it
     * can reduce its allocated objects.
     */
    fun onLowMemory() {
        // Removing the all the thumbnails except for the selected session to
        // reduce memory consumption.
        sessions.forEach {
            if (it != selectedSession) {
                it.thumbnail = null
            }
        }
    }

    companion object {
        const val NO_SELECTION = -1
    }
}
