package me.saffer.sill.android.client

import me.saffer.sill.android.protocol.MacMenu
import me.saffer.sill.android.protocol.MacMenuItem

/**
 * One connection's view of the streamed app's menu bar (iOSClient/MacMenuState.swift): the top
 * level the Mac sends after this device's subscription, each menu's items fetched when it opens
 * (with the title it was shown under), and choices. A new version of the tree settles whatever waits
 * with "The menus changed…". Pure: the client sends what it returns and keeps the clock.
 */
class MenuState {
    sealed class Menu {
        data object Loading : Menu()
        data class Items(val items: List<MacMenuItem>, val more: Int?, val stale: Boolean, val note: String?) : Menu()
        data class Failed(val why: String) : Menu()
    }

    data class Request(val kind: Int, val token: Int, val version: Int?, val id: String?, val title: String?)

    var version: Int? = null; private set
    var app: String? = null; private set
    var top: List<MacMenuItem> = emptyList(); private set
    var topNote: String? = null; private set
    var topStale = false; private set
    /** Menus opened on this version, by id. */
    val menus = LinkedHashMap<String, Menu>()

    private val waiting = HashMap<Int, Pair<String, Long>>()   // token → (menu id, asked at)
    private val choices = HashMap<Int, Long>()                 // token → asked at

    companion object {
        private var nextToken = 1
        const val CHANGED = "The menus changed. Open the menu again."
    }

    fun subscribe(): Request = Request(27, nextToken++, null, null, null)

    /** Ask for one menu's items; nil when it is already loading. */
    fun open(item: MacMenuItem, now: Long): Request? {
        val id = item.id ?: return null
        if (menus[id] == Menu.Loading && waiting.values.any { it.first == id }) return null
        menus[id] = Menu.Loading
        val token = nextToken++
        waiting[token] = id to now
        return Request(27, token, version, id, item.title)
    }

    fun choose(item: MacMenuItem, now: Long): Request? {
        val id = item.id ?: return null
        if (!item.enabled || item.separator || item.submenu || choices.size >= 16) return null
        val token = nextToken++
        choices[token] = now
        return Request(25, token, version, id, item.title)
    }

    sealed class Outcome {
        data object TopLevel : Outcome()
        data object MenuItems : Outcome()
        data class Pressed(val ok: Boolean, val note: String?) : Outcome()
        data object Ignored : Outcome()
    }

    fun receive(m: MacMenu): Outcome {
        val answering = m.answering
        if (answering == null || m.menus != null && !waiting.containsKey(answering) && !choices.containsKey(answering)) {
            if (m.menus == null) return Outcome.Ignored
            if (m.version != version) {
                // A new tree: every menu shown or asked for under the old one says so (open it again).
                for (id in menus.keys.toList()) menus[id] = Menu.Failed(CHANGED)
                waiting.clear()
            }
            version = m.version
            app = m.app
            top = m.menus
            topStale = m.stale
            topNote = m.note
            return Outcome.TopLevel
        }
        waiting.remove(answering)?.let { (id, _) ->
            val items = m.items
            menus[id] = when {
                // An answer from another tree than the one the menu was asked under (MacMenuState rule 5).
                m.version != null && version != null && m.version != version -> Menu.Failed(CHANGED)
                items != null -> Menu.Items(items, m.more, m.stale, m.note)
                else -> Menu.Failed(m.note ?: CHANGED)
            }
            return Outcome.MenuItems
        }
        if (choices.remove(answering) != null) return Outcome.Pressed(m.pressed == true, m.note)
        return Outcome.Ignored
    }

    /** Requests unanswered past the timeout settle as failed; returns whether anything changed. */
    fun expire(now: Long, timeoutMs: Long): Boolean {
        var changed = false
        val it = waiting.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value.second > timeoutMs) {
                menus[e.value.first] = Menu.Failed("The Mac didn’t answer in time.")
                it.remove(); changed = true
            }
        }
        choices.entries.removeAll { now - it.value > timeoutMs }
        return changed
    }

    fun reset() {
        version = null; app = null; top = emptyList(); topNote = null; topStale = false
        menus.clear(); waiting.clear(); choices.clear()
    }
}
