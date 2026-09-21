package com.vivekkaushik.wrtpulse.ui

import com.vivekkaushik.wrtpulse.db.RouterEntity
import com.vivekkaushik.wrtpulse.ui.screens.effectiveGroup
import com.vivekkaushik.wrtpulse.ui.screens.groupNames
import com.vivekkaushik.wrtpulse.ui.screens.routerGroupName
import com.vivekkaushik.wrtpulse.ui.screens.routerSections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouterGroupsTest {

    private fun router(id: Long, name: String, group: String? = null, primary: String? = null, seen: Long = 0) = RouterEntity(
        id = id, name = name, host = "192.168.$id.1", port = 22, username = "root", model = "", summary = "",
        credential = null, lastSeenEpoch = seen, identity = "id-$id", meshPrimary = primary, groupName = group,
    )

    private val jio = router(1, "Jio", group = "Home", seen = 30)
    private val node = router(2, "Node-2", primary = "id-1", seen = 50)   // last seen after its primary
    private val deco = router(3, "Deco", seen = 40)
    private val office = router(4, "Office box", group = "office", seen = 10)

    @Test
    fun `a typed group is trimmed, collapsed, capped, and blank means none`() {
        assertEquals("Parents place", routerGroupName("  Parents   place  "))
        assertNull(routerGroupName("   "))
        assertNull(routerGroupName(""))
        assertEquals(24, routerGroupName("x".repeat(40))!!.length)
    }

    @Test
    fun `a mesh node with no group of its own sits with its primary`() {
        val all = listOf(jio, node, deco)
        assertEquals("Home", effectiveGroup(node, all))
        assertNull(effectiveGroup(deco, all))
        // Its own group wins over the inherited one.
        assertEquals("Garage", effectiveGroup(node.copy(groupName = "Garage"), all))
    }

    @Test
    fun `sections are named groups in alphabetical order, then the ungrouped last`() {
        val all = listOf(node, deco, jio, office)   // the list's own order: last seen first
        val sections = routerSections(all)
        assertEquals(listOf("Home", "office", null), sections.map { it.group })
        assertEquals(listOf("Deco"), sections.last().routers.map { it.name })
    }

    @Test
    fun `inside a section a node moves to sit right after its primary`() {
        val all = listOf(node, deco, jio, office)
        val home = routerSections(all).first { it.group == "Home" }
        assertEquals(listOf("Jio", "Node-2"), home.routers.map { it.name })
    }

    @Test
    fun `a node whose primary is hidden by a search still keeps its primary's group`() {
        val all = listOf(jio, node, deco)
        val shown = listOf(node, deco)   // "node" matched the search, the primary did not
        val sections = routerSections(shown, all)
        assertEquals(listOf("Home", null), sections.map { it.group })
        assertEquals(listOf("Node-2"), sections.first().routers.map { it.name })
    }

    @Test
    fun `with no groups at all the list is one unnamed section, as it always was`() {
        val all = listOf(node, deco, router(5, "Plain"))
        val sections = routerSections(all)
        assertEquals(1, sections.size)
        assertNull(sections.single().group)
        // and it keeps the incoming order, node included, when there is no primary here to follow
        assertEquals(listOf("Node-2", "Deco", "Plain"), sections.single().routers.map { it.name })
    }

    @Test
    fun `a pinned group goes to the top of the sections and the chips, the rest stay alphabetical`() {
        val all = listOf(node, deco, jio, office, router(6, "Attic", group = "attic"))
        // Unpinned: alphabetical, ungrouped last.
        assertEquals(listOf("attic", "Home", "office", null), routerSections(all).map { it.group })
        // Pin office: it leads; the others keep their order behind it.
        assertEquals(listOf("office", "attic", "Home", null), routerSections(all, all, setOf("office")).map { it.group })
        assertEquals(listOf("office", "attic", "Home"), groupNames(all, setOf("office")))
        // Two pins are alphabetical among themselves.
        assertEquals(listOf("Home", "office", "attic", null), routerSections(all, all, setOf("office", "Home")).map { it.group })
        // A pin on a name nothing carries changes nothing.
        assertEquals(listOf("attic", "Home", "office"), groupNames(all, setOf("Garage")))
    }

    @Test
    fun `the chips list every group once, in a fixed order, nodes counted through their primary`() {
        assertEquals(listOf("Home", "office"), groupNames(listOf(node, deco, jio, office)))
        assertEquals(emptyList<String>(), groupNames(listOf(deco)))
    }
}
