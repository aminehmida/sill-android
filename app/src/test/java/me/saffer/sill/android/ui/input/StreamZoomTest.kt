package me.saffer.sill.android.ui.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.random.Random

/** StreamZoom and ZoomStroke, after iOS's Tests/checks/stream-zoom. */
class StreamZoomTest {
    private fun near(a: Double, b: Double, tol: Double = 0.001) = abs(a - b) <= tol
    private fun near(a: Pt, b: Pt, tol: Double = 0.001) = near(a.x, b.x, tol) && near(a.y, b.y, tol)
    private fun zoomed(f: Double, at: Pt, w: Double, h: Double) = StreamZoom().pinch(f, at, at, w, h)
    private fun covers(z: StreamZoom, w: Double, h: Double): Boolean {
        val tl = z.zoomed(Pt(0.0, 0.0), w, h); val br = z.zoomed(Pt(w, h), w, h)
        return tl.x <= 0.001 && tl.y <= 0.001 && br.x >= w - 0.001 && br.y >= h - 0.001
    }

    @Test fun pinchBasics() {
        val w = 400.0; val h = 300.0
        var z = StreamZoom()
        assertTrue(z.scale == 1.0 && z.offsetX == 0.0 && !z.isZoomed)
        z = z.pinch(2.0, Pt(200.0, 150.0), Pt(200.0, 150.0), w, h)
        assertTrue("2× at the middle", near(z.scale, 2.0) && near(z.offsetX, 0.0) && near(z.offsetY, 0.0) && z.isZoomed)
        z = z.pinch(0.75, Pt(200.0, 150.0), Pt(200.0, 150.0), w, h)
        assertTrue("a slight pinch out stays zoomed", near(z.scale, 1.5) && z.isZoomed)
        z = z.pinch(0.5, Pt(100.0, 80.0), Pt(100.0, 80.0), w, h)
        assertTrue("all the way out: the whole picture", z.scale == 1.0 && z.offsetX == 0.0 && z.offsetY == 0.0 && !z.isZoomed)
        z = z.pinch(10.0, Pt(200.0, 150.0), Pt(200.0, 150.0), w, h)
        assertEquals("at most 5×", 5.0, z.scale, 0.0)
        var m = zoomed(2.0, Pt(150.0, 120.0), w, h)
        assertTrue("the pinch's point stays under it", near(m.unzoomed(Pt(150.0, 120.0), w, h), Pt(150.0, 120.0)) && near(m.offsetX, 50.0) && near(m.offsetY, 30.0))
        m = m.pinch(1.0, Pt(150.0, 120.0), Pt(170.0, 130.0), w, h)
        assertTrue("the midpoint moves the picture", near(m.offsetX, 70.0) && near(m.offsetY, 40.0))
        m = m.pinch(1.0, Pt(0.0, 0.0), Pt(1000.0, -1000.0), w, h)
        assertTrue("the offset is held to the pane's edges", near(m.offsetX, 200.0) && near(m.offsetY, -150.0))
        val d = m.unzoomedDelta(40.0, -20.0)
        assertTrue("a scroll at 2× is half as far", near(d.x, 20.0) && near(d.y, -10.0))
    }

    @Test fun rangeAndSettle() {
        val w = 1000.0; val h = 650.0
        var a = zoomed(3.0, Pt(700.0, 200.0), w, h)
        a = a.pinch(0.9, Pt(700.0, 200.0), Pt(700.0, 200.0), w, h)
        assertTrue(near(a.scale, 2.7) && a.isZoomed)
        a = a.pinch(0.4, Pt(700.0, 200.0), Pt(700.0, 200.0), w, h)
        assertTrue(near(a.scale, 1.08) && a.isZoomed)
        a = a.settle()
        assertTrue("1.08× is kept at the lift", near(a.scale, 1.08))
        a = a.pinch(1.015 / 1.08, Pt(700.0, 200.0), Pt(700.0, 200.0), w, h)
        assertTrue(near(a.scale, 1.015) && a.isZoomed)
        a = a.settle()
        assertTrue("within 2 %: the whole picture", a.scale == 1.0 && a.offsetX == 0.0 && !a.isZoomed)
        assertTrue("exactly 1.02× stays", near(zoomed(1.02, Pt(100.0, 100.0), w, h).settle().scale, 1.02))
        val c = zoomed(4.0, Pt(900.0, 600.0), w, h).pinch(0.2, Pt(10.0, 10.0), Pt(990.0, 640.0), w, h)
        assertTrue("past 1×: whole, centred", c.scale == 1.0 && c.offsetX == 0.0 && c.offsetY == 0.0)
        var f = zoomed(2.0, Pt(500.0, 300.0), w, h).pinch(4.0, Pt(500.0, 300.0), Pt(500.0, 300.0), w, h)
        assertEquals(5.0, f.scale, 0.0)
        f = f.pinch(3.0, Pt(500.0, 300.0), Pt(520.0, 300.0), w, h)
        assertTrue("at 5× a spread only moves", f.scale == 5.0 && near(f.offsetX, 20.0))
        val g = StreamZoom().pinch(0.5, Pt(500.0, 300.0), Pt(600.0, 300.0), w, h)
        assertTrue("at 1× a pinch in does nothing", g.scale == 1.0 && g.offsetX == 0.0)
        var n = StreamZoom()
        n = n.pinch(2.0, Pt(1.0, 1.0), Pt(1.0, 1.0), 0.0, 0.0)
        n = n.pinch(0.0, Pt(1.0, 1.0), Pt(1.0, 1.0), w, h)
        n = n.pinch(Double.POSITIVE_INFINITY, Pt(1.0, 1.0), Pt(1.0, 1.0), w, h)
        n = n.pinch(-2.0, Pt(1.0, 1.0), Pt(1.0, 1.0), w, h)
        assertEquals("no pane, 0, ∞ or below 0: nothing", StreamZoom(), n)
    }

    @Test fun mapping() {
        val w = 1000.0; val h = 650.0
        val one = StreamZoom()
        assertEquals(Pt(123.0, 456.0), one.unzoomed(Pt(123.0, 456.0), w, h))
        val a = zoomed(2.5, Pt(300.0, 260.0), w, h)
        val u = Pt(420.0, 330.0)
        val shown = Pt(500 + 2.5 * (420 - 500) + a.offsetX, 325 + 2.5 * (330 - 325) + a.offsetY)
        assertTrue("c + s(u − c) + o", near(a.zoomed(u, w, h), shown))
        assertTrue("its inverse", near(a.unzoomed(shown, w, h), u))
        val v = a.visibleRect(w, h)
        assertTrue("400×260 shows at 2.5×", near(v[2], 400.0) && near(v[3], 260.0))
        val rng = Random(7)
        repeat(5000) {
            val pw = rng.nextDouble(200.0, 1400.0); val ph = rng.nextDouble(150.0, 1000.0)
            var z = StreamZoom()
            repeat(rng.nextInt(1, 6)) {
                z = z.pinch(rng.nextDouble(0.5, 2.5),
                    Pt(rng.nextDouble(-50.0, pw + 50), rng.nextDouble(-50.0, ph + 50)),
                    Pt(rng.nextDouble(-50.0, pw + 50), rng.nextDouble(-50.0, ph + 50)), pw, ph)
            }
            val p = Pt(rng.nextDouble(0.0, pw), rng.nextDouble(0.0, ph))
            assertTrue(near(z.zoomed(z.unzoomed(p, pw, ph), pw, ph), p, 0.01))
            assertTrue(covers(z, pw, ph) && z.scale in 1.0..5.0)
        }
    }

    @Test fun anotherPane() {
        val lw = 1000.0; val lh = 650.0; val pw = 690.0; val ph = 450.0
        var a = zoomed(2.5, Pt(300.0, 200.0), lw, lh)
        val middle = a.unzoomed(Pt(500.0, 325.0), lw, lh)
        a = a.resized(pw, ph)
        val now = a.unzoomed(Pt(345.0, 225.0), pw, ph)
        assertTrue("a turn keeps the scale", near(a.scale, 2.5))
        assertTrue("and the point at the middle", near(Pt(now.x / pw, now.y / ph), Pt(middle.x / lw, middle.y / lh)))
        assertTrue(covers(a, pw, ph) && a.paneW == pw)
        val b = StreamZoom().resized(pw, ph)
        assertTrue("at 1× a pane is only remembered", b.scale == 1.0 && b.offsetX == 0.0 && b.paneW == pw)
        val c = zoomed(3.0, Pt(900.0, 600.0), lw, lh)
        assertEquals("no size: nothing", c, c.resized(0.0, 0.0).resized(-10.0, 40.0))
    }

    /** A stroke step by step, as the touch view feeds it. */
    private class Rig(var zoom: StreamZoom = StreamZoom(), val w: Double = 1000.0, val h: Double = 650.0) {
        val stroke = ZoomStroke()
        var scrolling = false
        var panBegun = false
        fun down(id: Int, x: Double, y: Double) = stroke.down(id, Pt(x, y), zoom)
        fun move(id: Int, x: Double, y: Double) { zoom = stroke.moved(mapOf(id to Pt(x, y)), zoom, w, h) }
        fun moves(vararg m: Pair<Int, Pair<Double, Double>>) { zoom = stroke.moved(m.associate { it.first to Pt(it.second.first, it.second.second) }, zoom, w, h) }
        fun up(id: Int) { zoom = stroke.up(id, zoom) }
        fun pan() { if (panBegun) return; panBegun = true; stroke.panBegan(); scrolling = stroke.scrollsMac }
        fun silence() { zoom = stroke.silenced(zoom) }
    }

    private infix fun Double.at(y: Double) = this to y

    @Test fun oneFinger() {
        val r = Rig()
        r.down(0, 500.0, 300.0)
        assertEquals(ZoomStroke.Owner.UNDECIDED, r.stroke.owner)
        r.move(0, 505.0, 300.0); r.move(0, 512.0, 300.0); r.pan(); r.move(0, 560.0, 300.0)
        assertTrue("one finger's pan: the Mac's scroll", r.stroke.owner == ZoomStroke.Owner.MAC && r.scrolling && r.zoom == StreamZoom())
        r.up(0)
        assertTrue(r.stroke.owner == ZoomStroke.Owner.NONE && r.stroke.fingers == 0)
        val s = Rig(zoomed(3.0, Pt(300.0, 300.0), 1000.0, 650.0))
        val was = s.zoom
        s.down(0, 500.0, 300.0); s.move(0, 520.0, 300.0); s.pan(); s.move(0, 600.0, 340.0); s.up(0)
        assertTrue("one finger on a zoomed picture scrolls the Mac", s.scrolling && s.zoom == was)
    }

    @Test fun twoFingersAtOne() {
        val r = Rig()
        r.down(0, 460.0, 400.0); r.down(1, 540.0, 400.0)
        for (k in 1..3) r.moves(0 to (460.0 at 400.0 - k * 4), 1 to (540.0 at 400.0 - k * 4))
        r.pan()
        for (k in 4..30) r.moves(0 to (460.0 at 400.0 - k * 4), 1 to (540.0 at 400.0 - k * 4))
        assertTrue("two fingers in parallel: the Mac's scroll", r.scrolling && r.stroke.owner == ZoomStroke.Owner.MAC && r.zoom == StreamZoom())

        // A spread about a still midpoint.
        val s = Rig()
        s.down(0, 470.0, 300.0); s.down(1, 530.0, 300.0)
        s.moves(0 to (466.0 at 300.0), 1 to (534.0 at 300.0))
        assertTrue("8 px: undecided", s.stroke.owner == ZoomStroke.Owner.UNDECIDED && s.zoom == StreamZoom())
        s.moves(0 to (463.0 at 300.0), 1 to (537.0 at 300.0))
        assertTrue("14 px: the picture's", s.stroke.owner == ZoomStroke.Owner.PICTURE && !s.stroke.scrollsMac)
        assertTrue("caught up from where they landed", near(s.zoom.scale, 74.0 / 60.0))
        for (x in 460 downTo 425 step 5) s.moves(0 to (x.toDouble() at 300.0), 1 to ((1000 - x).toDouble() at 300.0))
        s.pan()
        assertTrue("a pan later changes nothing", s.stroke.owner == ZoomStroke.Owner.PICTURE && !s.scrolling)
        assertTrue(near(s.zoom.scale, 2.5))
        s.up(0)
        val held = s.zoom
        for (k in 1..20) s.move(1, 575.0 + k * 10, 300.0 + k * 5)
        assertTrue("the other finger moving on: nothing", s.zoom == held && !s.scrolling)
        s.up(1)
        assertTrue(s.stroke.owner == ZoomStroke.Owner.NONE && near(s.zoom.scale, 2.5))
    }

    @Test fun flickAndTies() {
        val r = Rig()
        r.down(0, 460.0, 400.0); r.down(1, 540.0, 400.0)
        r.moves(0 to (452.5 at 370.0), 1 to (547.5 at 370.0))
        assertTrue("more travel than spacing: undecided", r.stroke.owner == ZoomStroke.Owner.UNDECIDED)
        r.pan()
        assertTrue("then the Mac's", r.scrolling && r.zoom == StreamZoom())
        val t = Rig()
        t.down(0, 400.0, 300.0); t.down(1, 500.0, 300.0)
        t.moves(0 to (405.0 at 300.0), 1 to (515.0 at 300.0))
        t.pan()
        assertTrue("a tie is the Mac's", t.stroke.owner == ZoomStroke.Owner.MAC && t.scrolling)
        val u = Rig()
        u.down(0, 400.0, 300.0); u.down(1, 500.0, 300.0)
        u.moves(0 to (405.0 at 300.0), 1 to (516.0 at 300.0))
        u.pan()
        assertTrue("spacing 11, midpoint 10.5: the picture's", u.stroke.owner == ZoomStroke.Owner.PICTURE && !u.scrolling)
        val v = Rig()
        v.down(0, 500.0, 300.0)
        for (k in 1..4) v.move(0, 500.0, 300.0 - k * 5)
        v.pan()
        v.down(1, 600.0, 280.0)
        for (k in 1..20) v.moves(0 to (500.0 - k * 6 at 280.0), 1 to (600.0 + k * 6 at 280.0))
        assertTrue("a scroll a second finger joins never zooms", v.scrolling && v.stroke.owner == ZoomStroke.Owner.MAC && v.zoom == StreamZoom())
    }

    @Test fun twoFingersOnAZoomedPicture() {
        val w = 1000.0; val h = 650.0
        val r = Rig(zoomed(2.0, Pt(500.0, 325.0), w, h))
        r.down(0, 450.0, 300.0); r.down(1, 550.0, 300.0)
        assertTrue("the picture's at once", r.stroke.owner == ZoomStroke.Owner.PICTURE)
        for (k in 1..10) r.moves(0 to (450.0 + k * 5 at 300.0 + k * 3), 1 to (550.0 + k * 5 at 300.0 + k * 3))
        r.pan()
        assertTrue("moved together: the picture moves", near(r.zoom.offsetX, 50.0) && near(r.zoom.offsetY, 30.0) && near(r.zoom.scale, 2.0) && !r.scrolling)
        r.up(0); r.up(1)
        assertTrue(near(r.zoom.scale, 2.0))
        // Left within 2 % of the whole: the whole one at the lift.
        val t = Rig(zoomed(1.5, Pt(500.0, 325.0), w, h))
        t.down(0, 440.0, 300.0); t.down(1, 560.0, 300.0)
        t.moves(0 to (460.5 at 300.0), 1 to (539.5 at 300.0))
        t.moves(0 to (460.5 at 300.0), 1 to (540.3 at 300.0))
        val during = t.zoom.scale
        t.up(0); t.up(1)
        assertTrue(during > 1 && during < 1.02 && t.zoom.scale == 1.0 && t.zoom.offsetX == 0.0)
    }

    @Test fun threeFingers() {
        val w = 1000.0; val h = 650.0
        val start = zoomed(1.5, Pt(400.0, 300.0), w, h)
        val r = Rig(start)
        r.down(0, 450.0, 300.0); r.down(1, 550.0, 300.0)
        r.moves(0 to (440.0 at 300.0), 1 to (560.0 at 300.0))
        assertTrue(r.zoom != start)
        r.silence(); r.down(2, 500.0, 380.0)
        assertTrue("three fingers: back as the stroke found it", r.stroke.owner == ZoomStroke.Owner.GESTURE && r.zoom == start)
        r.moves(0 to (400.0 at 300.0), 1 to (600.0 at 300.0), 2 to (500.0 at 420.0))
        r.pan()
        assertTrue(r.zoom == start && !r.scrolling)
        r.up(0); r.up(1); r.up(2)
        assertTrue(r.stroke.owner == ZoomStroke.Owner.NONE && r.zoom == start)
        // Changed meanwhile by something else: left as it is.
        val t = Rig(start)
        t.down(0, 450.0, 300.0); t.down(1, 550.0, 300.0)
        t.moves(0 to (430.0 at 300.0), 1 to (570.0 at 300.0))
        t.zoom = t.zoom.reset()
        t.silence()
        assertTrue(t.zoom.scale == 1.0 && t.stroke.owner == ZoomStroke.Owner.GESTURE)
    }

    @Test fun randomStrokes() {
        val w = 1000.0; val h = 650.0
        val rng = Random(65)
        repeat(20_000) {
            val start = if (rng.nextBoolean()) StreamZoom() else zoomed(rng.nextDouble(1.1, 5.0), Pt(rng.nextDouble(0.0, 1000.0), rng.nextDouble(0.0, 650.0)), w, h)
            val r = Rig(start)
            val fingers = rng.nextInt(1, 4)
            val parallel = rng.nextBoolean()
            val landed = (0 until fingers).map { Pt(rng.nextDouble(100.0, 900.0), rng.nextDouble(100.0, 550.0)) }
            val positions = landed.toMutableList()
            val dir = Pt(rng.nextDouble(-8.0, 8.0), rng.nextDouble(-8.0, 8.0))
            val spread = rng.nextDouble(0.9, 1.1)
            val owners = ArrayList<ZoomStroke.Owner>()
            landed.forEachIndexed { i, p -> if (i == 2) r.silence(); r.down(i, p.x, p.y) }
            val steps = rng.nextInt(1, 31)
            val lift = rng.nextInt(0, steps + 1)
            val lifted = HashSet<Int>()
            for (step in 1..steps) {
                val before = r.zoom; val owner = r.stroke.owner
                if (step == lift && fingers >= 2) { lifted += 0; r.up(0) }
                val event = HashMap<Int, Pt>()
                val c = Pt(landed.sumOf { it.x } / fingers, landed.sumOf { it.y } / fingers)
                for (i in 0 until fingers) if (i !in lifted) {
                    val target = if (parallel) Pt(landed[i].x + dir.x * step, landed[i].y + dir.y * step)
                    else Pt(c.x + (landed[i].x - c.x) * spread.pow(step), c.y + (landed[i].y - c.y) * spread.pow(step))
                    positions[i] = target
                    event[i] = target
                }
                r.zoom = r.stroke.moved(event, r.zoom, w, h)
                val down = (0 until fingers).filter { it !in lifted }
                if (down.isNotEmpty()) {
                    val now = Pt(down.sumOf { positions[it].x } / down.size, down.sumOf { positions[it].y } / down.size)
                    val then = Pt(down.sumOf { landed[it].x } / down.size, down.sumOf { landed[it].y } / down.size)
                    if (hypot(now.x - then.x, now.y - then.y) >= 10) r.pan()
                }
                assertFalse("the zoom changes only while the picture owns the stroke",
                    r.zoom != before && !(owner == ZoomStroke.Owner.PICTURE || r.stroke.owner == ZoomStroke.Owner.PICTURE))
                assertTrue("1× to 5×, covering the pane", r.zoom.scale in 1.0..5.0 && covers(r.zoom, w, h))
                owners += r.stroke.owner
            }
            assertFalse("a stroke the Mac's scroll began never zooms", r.scrolling && r.zoom != start)
            assertFalse("three fingers leave the picture as found", fingers == 3 && r.zoom != start)
            assertFalse("one finger never zooms", fingers == 1 && r.zoom != start)
            assertFalse("two parallel fingers on the whole picture are never the picture's",
                fingers == 2 && parallel && !start.isZoomed && ZoomStroke.Owner.PICTURE in owners)
            var decided: ZoomStroke.Owner? = null
            for (o in owners) if (o == ZoomStroke.Owner.MAC || o == ZoomStroke.Owner.PICTURE || o == ZoomStroke.Owner.GESTURE) {
                val d = decided
                assertFalse("a decided stroke keeps its owner", d != null && d != o && o != ZoomStroke.Owner.GESTURE)
                decided = o
            }
            for (i in 0 until fingers) if (i !in lifted) r.up(i)
            assertEquals(ZoomStroke.Owner.NONE, r.stroke.owner)
        }
    }

    @Test fun zoomStateFollowsTheSource() {
        val s = StreamZoomState()
        s.follow("desktop")
        s.set(zoomed(2.0, Pt(10.0, 10.0), 100.0, 100.0))
        s.follow("desktop")
        assertTrue("the same source (a turn) keeps the zoom", s.zoom.isZoomed)
        s.follow("window 7")
        assertFalse("another source: the whole picture", s.zoom.isZoomed)
    }
}
